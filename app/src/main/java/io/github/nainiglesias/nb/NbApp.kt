package io.github.nainiglesias.nb

import android.app.Application
import io.github.nainiglesias.nb.data.ProjectStore
import io.github.nainiglesias.nb.data.RecentFiles
import io.github.nainiglesias.nb.kernel.PythonKernel

class NbApp : Application() {
    val kernel by lazy { PythonKernel(this) }
    val recentFiles by lazy { RecentFiles(this) }
    val projects by lazy { ProjectStore(this) }
}
