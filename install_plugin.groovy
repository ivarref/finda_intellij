import com.intellij.openapi.application.PathManager

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

// Usage:
//   /Applications/IntelliJ\ IDEA.app/Contents/MacOS/idea ideScript install_plugin.groovy my_plugin.zip

if (!args || args.length < 1) {
    println "Usage: ideScript install_plugin.groovy <plugin.zip>"
    return
}

def pluginZip = new File(args[0])
if (!pluginZip.exists()) {
    println "Error: file not found: ${args[0]}"
    return
}

def pluginsDir = Paths.get(PathManager.getPluginsPath())
println "Installing ${pluginZip.name} into ${pluginsDir} ..."

def zis = new ZipInputStream(new FileInputStream(pluginZip))
def entry
while ((entry = zis.nextEntry) != null) {
    def outPath = pluginsDir.resolve(entry.name)
    if (entry.directory) {
        Files.createDirectories(outPath)
    } else {
        Files.createDirectories(outPath.parent)
        Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING)
    }
    zis.closeEntry()
}
zis.close()

println "Done! Restart IntelliJ IDEA to activate the plugin."
