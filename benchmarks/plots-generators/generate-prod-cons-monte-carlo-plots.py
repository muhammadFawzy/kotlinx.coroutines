import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.ticker import FormatStrFormatter
from matplotlib.backends.backend_pdf import PdfPages

inputFile = "resultsProdCons.csv"
outputFile = 'plotsProdCons.pdf'

markers = ['.', 'v', '^', '1', '2', '8', 'p', 'P', 'x', 'D', 'd', 's']
colours = ['black', 'silver', 'red', 'gold', 'sienna', 'olivedrab', 'lightseagreen', 'navy', 'blue', 'm', 'crimson', 'yellow', 'orangered', 'slateblue', 'aqua']

def next_colour():
    for colour in colours:
        yield colour

def next_marker():
    for marker in markers:
        yield marker

def draw(data, loadMode, dispatcherType, selectMode, plt):
    plt.xscale('log', basex=2)
    plt.gca().xaxis.set_major_formatter(FormatStrFormatter('%0.f'))
    plt.grid(linewidth='0.5', color='lightgray')
    plt.ylabel('time (ms)')
    plt.xlabel('threads')
    plt.xticks(data.threads.unique())

    colourGen = next_colour()
    markerGen = next_marker()
    for channel in data.channel.unique():
        genColour = next(colourGen)
        genMarker = next(markerGen)
        res = data[(data.loadMode == loadMode) & (data.dispatcherType == dispatcherType) & (data.selectMode == selectMode) & (data.channel == channel)]
        plt.plot(res.threads, res.result, label="channel={}".format(channel), color=genColour, marker=genMarker)

def genFile(pdf):
    data = pd.read_table(inputFile, sep=",")
    for loadMode in data.loadMode.unique():
        for dispatcherType in data.dispatcherType.unique():
            for selectMode in data.selectMode.unique():
                fig = plt.figure(figsize=(20, 15))
                draw(data, loadMode, dispatcherType, selectMode, plt)
                fig.suptitle("loadMode={},dispatcherType={},selectMode={}".format(loadMode, dispatcherType, selectMode), fontsize=12, y=1)
                plt.legend(loc='upper center', borderpad=0, ncol=4, frameon=False, borderaxespad=4, prop={'size': 8})
                plt.tight_layout(pad=12, w_pad=2, h_pad=1)
                pdf.savefig(bbox_inches='tight')

with PdfPages(outputFile) as pdf:
    genFile(pdf)