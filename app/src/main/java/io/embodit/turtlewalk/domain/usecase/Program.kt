package io.embodit.turtlewalk.domain.usecase

import io.embodit.turtlewalk.domain.model.Block
import io.embodit.turtlewalk.domain.model.BlockKind
import io.embodit.turtlewalk.domain.model.TurtleRules
import io.embodit.turtlewalk.domain.model.formatValue

/** Where the next block goes: [index] within the body of repeat [parent] (null = the top level). */
data class Cursor(val parent: Int?, val index: Int)

/** Block trees are immutable; every edit returns a new program (design `rules.editing`). */
object Program {

    fun count(blocks: List<Block>): Int = blocks.sumOf { 1 + count(it.body) }

    fun maxId(blocks: List<Block>): Int = blocks.maxOfOrNull { maxOf(it.id, maxId(it.body)) } ?: 0

    fun find(blocks: List<Block>, id: Int): Block? {
        for (b in blocks) {
            if (b.id == id) return b
            find(b.body, id)?.let { return it }
        }
        return null
    }

    /** The repeat that holds block [id] (null at the top level or when absent) and the index in its body. */
    fun locate(blocks: List<Block>, id: Int, parent: Int? = null): Cursor? {
        blocks.forEachIndexed { i, b ->
            if (b.id == id) return Cursor(parent, i)
            locate(b.body, id, b.id)?.let { return it }
        }
        return null
    }

    /** Depth of the body of repeat [parent]: 0 = top level, 1 = inside one repeat, ... */
    fun depthOf(blocks: List<Block>, parent: Int?): Int {
        if (parent == null) return 0
        fun walk(list: List<Block>, depth: Int): Int? {
            for (b in list) {
                if (b.id == parent) return depth + 1
                walk(b.body, depth + 1)?.let { return it }
            }
            return null
        }
        return walk(blocks, 0) ?: 0
    }

    fun bodyOf(blocks: List<Block>, parent: Int?): List<Block> = if (parent == null) blocks else find(blocks, parent)?.body ?: emptyList()

    /** Replaces the body of [parent] (null = the top level). */
    fun withBody(blocks: List<Block>, parent: Int?, body: List<Block>): List<Block> {
        if (parent == null) return body
        return blocks.map { b -> if (b.id == parent) b.copy(body = body) else if (b.body.isEmpty()) b else b.copy(body = withBody(b.body, parent, body)) }
    }

    /** Why a block of [kind] cannot go to [cursor], or null when it can. */
    fun refusal(blocks: List<Block>, cursor: Cursor, kind: BlockKind): Refusal? {
        if (cursor.parent == null && blocks.size >= TurtleRules.MAX_TOP_BLOCKS) return Refusal.TOO_MANY_BLOCKS
        if (kind == BlockKind.REPEAT && depthOf(blocks, cursor.parent) >= TurtleRules.MAX_NESTING) return Refusal.TOO_DEEP
        return null
    }

    /** Inserts a fresh block of [kind] at [cursor]; returns the program, the new block's id and the cursor after it. */
    fun insert(blocks: List<Block>, cursor: Cursor, kind: BlockKind): Triple<List<Block>, Int, Cursor>? {
        if (refusal(blocks, cursor, kind) != null) return null
        val id = maxId(blocks) + 1
        val body = bodyOf(blocks, cursor.parent).toMutableList()
        val at = cursor.index.coerceIn(0, body.size)
        body.add(at, Block(id, kind, kind.defaultValue))
        return Triple(withBody(blocks, cursor.parent, body), id, Cursor(cursor.parent, at + 1))
    }

    fun setValue(blocks: List<Block>, id: Int, value: Float): List<Block> =
        blocks.map { b -> if (b.id == id) b.copy(value = value) else if (b.body.isEmpty()) b else b.copy(body = setValue(b.body, id, value)) }

    /** Deletes block [id] (a repeat goes with its body); returns the program and the cursor where it stood. */
    fun delete(blocks: List<Block>, id: Int): Pair<List<Block>, Cursor> {
        val at = locate(blocks, id) ?: return blocks to Cursor(null, blocks.size)
        val body = bodyOf(blocks, at.parent).toMutableList()
        body.removeAt(at.index)
        return withBody(blocks, at.parent, body) to at
    }

    /**
     * Moves block [id] one place up ([up]) or down. At the edge of a repeat body it leaves the bracket; next to a
     * repeat it enters that bracket (at its end going up, at its start going down), as long as the nesting allows.
     */
    fun move(blocks: List<Block>, id: Int, up: Boolean): List<Block> {
        val at = locate(blocks, id) ?: return blocks
        val block = find(blocks, id) ?: return blocks
        val body = bodyOf(blocks, at.parent)
        val i = at.index
        val edge = if (up) i == 0 else i == body.size - 1
        if (edge) {
            // leave the bracket: before (up) or after (down) the repeat that holds it
            val parentId = at.parent ?: return blocks
            val outer = locate(blocks, parentId) ?: return blocks
            val (without, _) = delete(blocks, id)
            val outerBody = bodyOf(without, outer.parent).toMutableList()
            val pos = if (up) outer.index else outer.index + 1
            if (outer.parent == null && outerBody.size >= TurtleRules.MAX_TOP_BLOCKS) return blocks
            outerBody.add(pos, block)
            return withBody(without, outer.parent, outerBody)
        }
        val neighbour = body[if (up) i - 1 else i + 1]
        if (neighbour.kind == BlockKind.REPEAT) {
            // enter the neighbouring repeat if the nesting allows it
            val depthInside = depthOf(blocks, neighbour.id)
            val fits = block.kind != BlockKind.REPEAT || depthInside + nestingOf(block) <= TurtleRules.MAX_NESTING
            if (fits) {
                val (without, _) = delete(blocks, id)
                val inner = (find(without, neighbour.id)?.body ?: emptyList()).toMutableList()
                if (up) inner.add(block) else inner.add(0, block)
                return withBody(without, neighbour.id, inner)
            }
        }
        val swapped = body.toMutableList()
        val j = if (up) i - 1 else i + 1
        swapped[i] = swapped[j]
        swapped[j] = block
        return withBody(blocks, at.parent, swapped)
    }

    /** How many repeat levels [b] brings with it (a plain block 0, a repeat 1 + its deepest child). */
    fun nestingOf(b: Block): Int = if (b.kind != BlockKind.REPEAT) 0 else 1 + (b.body.maxOfOrNull { nestingOf(it) } ?: 0)

    /** Left and right turns swapped (the mirror check). */
    fun mirrored(blocks: List<Block>): List<Block> =
        blocks.map { b ->
            when (b.kind) {
                BlockKind.LEFT -> b.copy(kind = BlockKind.RIGHT)
                BlockKind.RIGHT -> b.copy(kind = BlockKind.LEFT)
                BlockKind.REPEAT -> b.copy(body = mirrored(b.body))
                else -> b
            }
        }

    // ------------------------------------------------------------------------------------------
    // compact notation (design `qaHooks.program`): F1 L90 R90 and R4[...] / X4[...] for repeat
    // ------------------------------------------------------------------------------------------

    fun encode(blocks: List<Block>): String =
        blocks.joinToString(" ") { b ->
            when (b.kind) {
                BlockKind.FORWARD -> "F" + formatValue(b.value)
                BlockKind.LEFT -> "L" + formatValue(b.value)
                BlockKind.RIGHT -> "R" + formatValue(b.value)
                BlockKind.REPEAT -> "R" + formatValue(b.value) + "[" + encode(b.body) + "]"
            }
        }

    /** Parses the compact notation; ids are numbered from 1 in reading order. Null on a syntax error. */
    fun parse(text: String): List<Block>? {
        var pos = 0
        var nextId = 1
        val s = text.trim()

        fun skip() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == ',' || s[pos] == '\t')) pos++
        }

        fun list(): List<Block>? {
            val out = ArrayList<Block>()
            while (true) {
                skip()
                if (pos >= s.length || s[pos] == ']') return out
                val letter = s[pos].uppercaseChar()
                pos++
                val start = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                val value = s.substring(start, pos).toFloatOrNull() ?: return null
                skip()
                val id = nextId++
                if (pos < s.length && s[pos] == '[') {
                    if (letter != 'R' && letter != 'X') return null
                    pos++
                    val body = list() ?: return null
                    if (pos >= s.length || s[pos] != ']') return null
                    pos++
                    out += Block(id, BlockKind.REPEAT, value, body)
                } else {
                    val kind =
                        when (letter) {
                            'F' -> BlockKind.FORWARD
                            'L' -> BlockKind.LEFT
                            'R' -> BlockKind.RIGHT
                            else -> return null
                        }
                    out += Block(id, kind, value)
                }
            }
        }
        val result = list() ?: return null
        skip()
        return if (pos == s.length) result else null
    }

    enum class Refusal { TOO_MANY_BLOCKS, TOO_DEEP }
}
