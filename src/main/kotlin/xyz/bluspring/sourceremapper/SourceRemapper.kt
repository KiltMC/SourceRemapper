package xyz.bluspring.sourceremapper

import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.tree.MemoryMappingTree
import java.io.File

object SourceRemapper {
    @JvmStatic
    fun main(args: Array<out String>) {
        val version = args[0]
        val copy = File("${args[1]}_copy")
        val mainPath = File(args[1])

        if (!copy.exists()) {
            mainPath.copyRecursively(copy)
        } else {
            copy.copyRecursively(mainPath, true)
        }

        decompile(mainPath, version, File("."))
    }

    fun remapDescriptor(descriptor: String, mappings: MemoryMappingTree): String {
        var formed = ""

        var incomplete = ""
        var inClass = false
        for (c in descriptor) {
            if (c == 'L' && !inClass)
                inClass = true

            if (inClass) {
                incomplete += c

                if (c == ';') {
                    inClass = false
                    formed += 'L'

                    val name = incomplete.removePrefix("L").removeSuffix(";")
                    formed += mappings.classes.firstOrNull { it.getName(0) == name }?.srcName ?: name

                    formed += ';'

                    incomplete = ""
                }
            } else {
                formed += c
            }
        }

        return formed
    }

    fun decompile(output: File, version: String, tempDir: File) {
        val mappingDownloader = MappingDownloader(version, tempDir)
        mappingDownloader.downloadFiles()

        val srg = MemoryMappingTree() // obf -> srg
        MappingReader.read(mappingDownloader.srgMappingsFile.reader(), srg)

        val mojmap = MemoryMappingTree() // obf -> moj
        MappingReader.read(mappingDownloader.mojangMappingsFile.reader(), mojmap)

        println("Mapping SRG directly to MojMap...")
        val srg2mojmap = mutableMapOf<String, String>()

        for (classMapping in srg.classes) {
            val mojClassMap = mojmap.classes.firstOrNull { it.getName(0) == classMapping.srcName } ?: continue

            for (field in classMapping.fields) {
                val srgName = field.getName("srg")!!
                if (!srgName.startsWith("f_") && !srgName.startsWith("m_"))
                    continue

                val mojField = mojClassMap.fields.firstOrNull { it.getName(0) == field.srcName } ?: continue
                srg2mojmap[srgName] = mojField.srcName
            }

            for (method in classMapping.methods) {
                val srgName = method.getName("srg")!!
                if (!srgName.startsWith("f_") && !srgName.startsWith("m_"))
                    continue

                val remappedDesc = remapDescriptor(method.srcDesc ?: "", mojmap)
                val mojMethod = mojClassMap.methods.firstOrNull { it.getName(0) == method.srcName && remappedDesc == it.srcDesc } ?: continue
                srg2mojmap[srgName] = mojMethod.srcName
            }
        }

        println("Finished mapping SRG to MojMap!")
        println("Proceeding with remapping files...")

        for (file in output.walk()) {
            if (file.extension != "java" && file.extension != "patch")
                continue

            var data = file.readText()
            println("Mapping file ${file.name}")

            for ((srgName, mojName) in srg2mojmap) {
                data = data.replace(srgName, mojName)
            }

            file.writeText(data)
        }

        println("Finished remapping files!")
    }
}
