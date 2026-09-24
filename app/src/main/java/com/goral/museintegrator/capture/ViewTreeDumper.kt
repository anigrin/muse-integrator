package com.goral.museintegrator.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** One flattened accessibility node, kept for the raw archive and for metadata parsing. */
data class FlatNode(
    val depth: Int,
    val className: String,
    val viewId: String,
    val text: String,
    val contentDescription: String,
    val bounds: Rect,
    val clickable: Boolean,
    val scrollable: Boolean
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    fun toJson(): JSONObject = JSONObject().apply {
        put("depth", depth)
        put("className", className)
        put("viewId", viewId)
        put("text", text)
        put("contentDescription", contentDescription)
        put("bounds", JSONObject().apply {
            put("left", bounds.left); put("top", bounds.top)
            put("right", bounds.right); put("bottom", bounds.bottom)
        })
        put("clickable", clickable)
        put("scrollable", scrollable)
    }
}

/**
 * Flattens the Muse window's accessibility tree.
 *
 * The Mind graph is canvas-drawn, so this holds none of the trace — but it does hold every
 * displayed number, and it costs tens of kilobytes. Preserving it is cheap insurance: a future
 * graph-v2 may want a field this version never parsed.
 */
object ViewTreeDumper {

    fun flatten(root: AccessibilityNodeInfo?): List<FlatNode> {
        val out = mutableListOf<FlatNode>()
        if (root != null) walk(root, 0, out)
        return out
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, out: MutableList<FlatNode>) {
        if (depth > 60 || out.size > 4000) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        out += FlatNode(
            depth = depth,
            className = node.className?.toString().orEmpty(),
            viewId = node.viewIdResourceName.orEmpty(),
            text = node.text?.toString().orEmpty(),
            contentDescription = node.contentDescription?.toString().orEmpty(),
            bounds = rect,
            clickable = node.isClickable,
            scrollable = node.isScrollable
        )
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, out)
        }
    }

    fun toJson(nodes: List<FlatNode>, packageName: String): String {
        val arr = JSONArray()
        nodes.forEach { arr.put(it.toJson()) }
        return JSONObject().apply {
            put("capturedAtEpochMs", System.currentTimeMillis())
            put("packageName", packageName)
            put("nodeCount", nodes.size)
            put("nodes", arr)
        }.toString(2)
    }
}
