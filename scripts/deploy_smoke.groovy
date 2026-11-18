// Run through Dropbox Fiji with its bundled Java 11:
// --default-gc --mem=512m --headless --console --run <this file>.
// FPB_SMOKE_HOME contains the copied basic/Control_S3.tif fixture.
import fpb.FPB
import fpb.FPBParameters
import fpb.render.ChannelColour
import javax.imageio.ImageIO
import groovy.json.JsonOutput

try {
    def home = new File(System.getenv('FPB_SMOKE_HOME'))
    def input = new File(home, 'input')
    def output = new File(input, 'AI_Exports')
    def origin = FPB.protectionDomain.codeSource.location.toURI().toString()
    assert origin ==~ /.*\/plugins\/FigurePanelBuilder-[0-9.]+\.jar/: origin
    def params = FPBParameters.builder(input)
        .channel(1, 'DAPI', ChannelColour.BLUE, 120, 4200)
        .pick('Control', 'S3').writeRecords(false).hideDisplay(true)
        .outputFolder(output).figureName('DeploymentSmoke').build()
    def result = FPB.runAndWrite(params)
    def figure = result.figureDirectory()
    def png = ImageIO.read(new File(figure, 'figure.png'))
    assert png != null && png.width > 0 && png.height > 0
    assert new File(figure, 'figure.tif').length() > 0
    assert new File(figure, 'figure.svg').text.contains('<svg')
    new File(home, 'runtime.json').text = JsonOutput.prettyPrint(JsonOutput.toJson([
        ok: true, route: 'Fiji headless guided export', loaded_from: origin,
        imagej_version: ij.IJ.getVersion(), java_version: System.getProperty('java.version'),
        width: png.width, height: png.height, outputs: ['figure.png','figure.tif','figure.svg']]))
    System.exit(0)
} catch (Throwable failure) {
    failure.printStackTrace()
    System.exit(1)
}
