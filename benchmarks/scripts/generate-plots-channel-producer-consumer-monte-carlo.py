import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.ticker import FormatStrFormatter
from matplotlib.backends.backend_pdf import PdfPages

inputFile = "resultsProdCons.csv"
outputFile = 'channel-producer-consumer-monte-carlo.pdf'

markers = ['.', 'v', '^', '1', '2', '8', 'p', 'P', 'x', 'D', 'd', 's']
colours = ['black', 'silver', 'red', 'gold', 'sienna', 'olivedrab', 'lightseagreen', 'navy', 'blue', 'm', 'crimson', 'yellow', 'orangered', 'slateblue', 'aqua']

def next_colour():
    for colour in colours:
        yield colour

def next_marker():
    for marker in markers:
        yield marker

def draw(data, with_balancing, dispatcher_type, with_select, plt):
    plt.xscale('log', basex=2)
    plt.gca().xaxis.set_major_formatter(FormatStrFormatter('%0.f'))
    plt.grid(linewidth='0.5', color='lightgray')
    plt.ylabel('time (ms)')
    plt.xlabel('threads')
    plt.xticks(data.threads.unique())

    colour_gen = next_colour()
    marker_gen = next_marker()
    for channel in data.channel.unique():
        gen_colour = next(colour_gen)
        gen_marker = next(marker_gen)
        res = data[(data.withBalancing == with_balancing) & (data.dispatcherType == dispatcher_type) & (data.withSelect == with_select) & (data.channel == channel)]
        plt.plot(res.threads, res.result, label="channel={}".format(channel), color=gen_colour, marker=gen_marker)

def gen_file(pdf):
    data = pd.read_table(inputFile, sep=",")
    for with_balancing in data.withBalancing.unique():
        for dispatcher_type in data.dispatcherType.unique():
            for with_select in data.withSelect.unique():
                fig = plt.figure(figsize=(20, 15))
                draw(data, with_balancing, dispatcher_type, with_select, plt)
                fig.suptitle("withBalancing={},dispatcherType={},withSelect={}".format(with_balancing, dispatcher_type, with_select), fontsize=12, y=1)
                plt.legend(loc='upper center', borderpad=0, ncol=4, frameon=False, borderaxespad=4, prop={'size': 8})
                plt.tight_layout(pad=12, w_pad=2, h_pad=1)
                pdf.savefig(bbox_inches='tight')

with PdfPages(outputFile) as pdf:
    gen_file(pdf)