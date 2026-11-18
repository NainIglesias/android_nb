"""matplotlib backend that sends figures to the notebook as PNG outputs.

Selected through MPLBACKEND=module://nb_mpl_backend (set by nbkernel).
Figures are shown when plt.show() is called and at the end of every cell.
"""

from matplotlib.backend_bases import FigureManagerBase
from matplotlib.backends.backend_agg import FigureCanvasAgg


class FigureCanvas(FigureCanvasAgg):
    manager_class = FigureManagerBase


FigureManager = FigureManagerBase


def show(*args, **kwargs):
    import nbkernel

    session = nbkernel.current_session()
    if session is not None:
        session._flush_figures()


def draw_if_interactive():
    pass
