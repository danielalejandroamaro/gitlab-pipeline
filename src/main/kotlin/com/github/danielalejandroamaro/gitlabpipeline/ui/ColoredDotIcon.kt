package com.github.danielalejandroamaro.gitlabpipeline.ui

import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** Filled circle of [color], scaled with the IDE's UI scale. */
class ColoredDotIcon(
    private val color: JBColor,
    sizeUnscaled: Int = 12,
) : Icon {

    private val size: Int = JBUIScale.scale(sizeUnscaled)

    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x, y, size - 1, size - 1)
        } finally {
            g2.dispose()
        }
    }

    companion object {
        // Light / dark variants — JBColor picks the right one based on theme.
        val GREEN = ColoredDotIcon(JBColor(Color(0x4CAF50), Color(0x5FB85F)))
        val RED   = ColoredDotIcon(JBColor(Color(0xE53935), Color(0xE57373)))
        val GREY  = ColoredDotIcon(JBColor(Color(0x9E9E9E), Color(0xBDBDBD)))
        val AMBER = ColoredDotIcon(JBColor(Color(0xF9A825), Color(0xFBC02D)))
    }
}
