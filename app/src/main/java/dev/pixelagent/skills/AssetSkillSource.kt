package dev.pixelagent.skills

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class AssetSkillSource(private val assets: AssetManager) : SkillSource {
    override fun directories(): List<String> = assets.list("skills").orEmpty().toList()
    override fun files(name: String): List<String> {
        require(SkillRegistry.validName(name))
        val result = mutableListOf<String>()
        fun visit(path: String, depth: Int) {
            require(depth <= 5 && result.size < 64) { "package_limit" }
            val children = assets.list("skills/$name/$path").orEmpty()
            if (children.isEmpty()) result.add(path)
            else children.forEach { visit(if (path.isEmpty()) it else "$path/$it", depth + 1) }
        }
        visit("", 0)
        return result
    }
    override fun read(name: String, path: String, maxBytes: Int): String {
        require(SkillRegistry.validName(name) && SkillRegistry.validPath(path))
        return assets.open("skills/$name/$path").use { stream ->
            val buffer = ByteArray(maxBytes + 1)
            var size = 0
            while (size < buffer.size) {
                val count = stream.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            require(size <= maxBytes) { "resource_size_limit" }
            val bytes = buffer.copyOf(size)
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }
    }
}
