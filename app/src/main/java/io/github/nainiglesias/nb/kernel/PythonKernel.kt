package io.github.nainiglesias.nb.kernel

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

data class ExecResult(val ok: Boolean, val executionCount: Int)

/** Called on the kernel thread while a cell runs. */
interface ExecutionListener {
    fun onOutput(output: JsonObject)

    /** Ask the user for input(); complete the future with null to cancel. */
    fun onInputRequest(prompt: String, password: Boolean, answer: CompletableFuture<String?>)
}

/**
 * The embedded Python interpreter. There is one per process, and every call
 * into Python runs on a single dedicated thread, so cells run one at a time.
 */
class PythonKernel(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "python-kernel") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var module: PyObject? = null
    @Volatile private var session: PyObject? = null

    @Volatile private var pendingInput: CompletableFuture<String?>? = null

    val workspace: File = File(appContext.filesDir, "workspace").apply { mkdirs() }

    /** Starts Python (slow the first time: it unpacks the standard library). Returns the version. */
    suspend fun start(): String = withContext(dispatcher) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
        val py = Python.getInstance()
        if (module == null) module = py.getModule("nbkernel")
        py.getModule("sys").get("version")!!.toString().substringBefore(' ')
    }

    /** Throw away all variables and start counting cells from 1 again. Cells run in [workdir]. */
    suspend fun newSession(workdir: File = workspace) = withContext(dispatcher) {
        start()
        session = module!!.callAttr("new_session", workdir.absolutePath)
    }

    suspend fun execute(code: String, listener: ExecutionListener): ExecResult = withContext(dispatcher) {
        if (session == null) newSession()
        val sink = Sink(listener)
        val result = session!!.callAttr("execute", code, sink).toString()
        val obj = Json.parseToJsonElement(result).jsonObject
        ExecResult(
            ok = obj["status"]?.jsonPrimitive?.content == "ok",
            executionCount = obj["execution_count"]!!.jsonPrimitive.int,
        )
    }

    /** Interrupt the running cell. Safe to call from any thread. */
    suspend fun interrupt() = withContext(Dispatchers.IO) {
        // Unblock a waiting input() first: it raises KeyboardInterrupt by itself.
        pendingInput?.complete(null)
        session?.callAttr("interrupt")
    }

    /** Methods called from Python (see nbkernel.py). */
    inner class Sink(private val listener: ExecutionListener) {
        @Suppress("unused")
        fun onOutput(json: String) {
            listener.onOutput(Json.parseToJsonElement(json).jsonObject)
        }

        @Suppress("unused")
        fun requestInput(prompt: String, password: Boolean): String? {
            val future = CompletableFuture<String?>()
            pendingInput = future
            return try {
                listener.onInputRequest(prompt, password, future)
                future.get()
            } finally {
                pendingInput = null
            }
        }
    }
}
