package com.muoniumplayer.core.interfaces;

import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.shader.Shaders;

import java.awt.*;

import static com.muoniumplayer.core.rendering.rendersystem.RenderSystem.DIVIDE_BY_255;


/**
 * @author IzumiiKonata
 * @since 2023/12/17
 */
public interface SharedRenderingConstants {

    default void roundedRectTextured(double x, double y, double width, double height, double texX, double texY, double u, double v, double radius, double expand, float alpha) {
        Shaders.RQT_SHADER.drawSpecial(x - expand, y - expand, width + expand * 2, height + expand * 2, texX, texY, u, v, radius, alpha);
    }

    default void roundedOutlineGradient(double x, double y, double width, double height, double radius, double thickness, Color bottomLeft, Color topLeft, Color bottomRight, Color topRight) {
        Shaders.ROGQ_SHADER.draw(x, y, width, height, radius, thickness, bottomLeft, topLeft, bottomRight, topRight);
    }

    default void roundedOutline(double x, double y, double width, double height, double radius, double thickness, Color outline) {
        Shaders.ROQ_SHADER.draw(x, y, width, height, radius, thickness, outline);
    }

    default void roundedOutline(double x, double y, double width, double height, double radius, double thickness, double expand, Color outline) {
        this.roundedOutline(x - expand, y - expand, width + expand * 2, height + expand * 2, radius, thickness, outline);
    }

    default void roundedRect(double x, double y, double width, double height, double radius, float r, float g, float b, float a) {
        Shaders.RQ_SHADER.draw(x, y, width, height, radius, r, g, b, a);
    }

    default void roundedRect(double x, double y, double width, double height, double radius, double expand, float r, float g, float b, float a) {
        roundedRect(x - expand, y - expand, width + expand * 2, height + expand * 2, radius, r, g, b, a);
    }

    default void roundedRect(double x, double y, double width, double height, double radius, int r, int g, int b, int a) {
        Shaders.RQ_SHADER.draw(x, y, width, height, radius, r, g, b, a);
    }

    default void roundedRect(double x, double y, double width, double height, double radius, int color) {

        float f = (color >> 24 & 255) * DIVIDE_BY_255;
        float f1 = (color >> 16 & 255) * DIVIDE_BY_255;
        float f2 = (color >> 8 & 255) * DIVIDE_BY_255;
        float f3 = (color & 255) * DIVIDE_BY_255;

        Shaders.RQ_SHADER.draw(x, y, width, height, radius, f1, f2, f3, f);
    }

    default void roundedRect(double x, double y, double width, double height, double radius, Color color) {
        Shaders.RQ_SHADER.draw(x, y, width, height, radius, color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
    }

    default void roundedRect(double x, double y, double width, double height, double radius, double expand, Color color) {
        Shaders.RQ_SHADER.draw(x - expand, y - expand, width + expand * 2, height + expand * 2, radius, color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
    }

    default void roundedRectTextured(double x, double y, double width, double height, double radius) {
        Shaders.RQT_SHADER.draw(x, y, width, height, radius, 1);
    }

    default void roundedRectTextured(double x, double y, double width, double height, double radius, float alpha) {
        Shaders.RQT_SHADER.draw(x, y, width, height, radius, alpha);
    }

    default void roundedRectTextured(double x, double y, double width, double height, double texX, double texY, double u, double v, double radius) {
        Shaders.RQT_SHADER.draw(x, y, width, height, texX, texY, u, v, radius, 1);
    }

    default void roundedRectTextured(double x, double y, double width, double height, double texX, double texY, double u, double v, double radius, float alpha) {
        Shaders.RQT_SHADER.draw(x, y, width, height, texX, texY, u, v, radius, alpha);
    }

    default void roundedRectGradientHorizontal(double x, double y, double width, double height, double radius, Color left, Color right) {
        Shaders.RQG_SHADER.draw(x, y, width, height, radius, left, left, right, right);
    }

    /**
     * 竖向渐变。传进来的颜色<b>包括 alpha</b>：{@code rqg.frag} 会对四个角做双线性插值，
     * 最终透明度是插出来的 alpha 乘上这一次绘制的全局 alpha。
     */
    default void roundedRectGradientVertical(double x, double y, double width, double height, double radius, Color top, Color bottom) {
        Shaders.RQG_SHADER.draw(x, y, width, height, radius, bottom, top, bottom, top);
    }

    default void drawGradientCornerLR(double x, double y, double width, double height, double radius, Color topLeft, Color bottomRight) {
        Color mixedColor = evenAdd(topLeft, bottomRight);
        Shaders.RQG_SHADER.draw(x, y, width, height, radius, mixedColor, topLeft, bottomRight, mixedColor);
    }

    default void drawGradientCornerRL(double x, double y, double width, double height, double radius, Color bottomLeft, Color topRight) {
        Color mixedColor = evenAdd(bottomLeft, topRight);
        Shaders.RQG_SHADER.draw(x, y, width, height, radius, bottomLeft, mixedColor, mixedColor, topRight);
    }

    /**
     * 两个颜色的等权中间色，供 {@link #drawGradientCornerLR} / {@link #drawGradientCornerRL}
     * 补出对角的两个角。
     *
     * <p>alpha 也要一起平均。三参数的 {@code Color} 构造器给出的是不透明色，于是两个半透明角之间
     * 会补出一个不透明的角，对角渐变上会出现一条突然变实的亮带。</p>
     */
    default Color evenAdd(Color a, Color b) {
        return new Color(a.getRed() / 2 + b.getRed() / 2, a.getGreen() / 2 + b.getGreen() / 2,
                a.getBlue() / 2 + b.getBlue() / 2, a.getAlpha() / 2 + b.getAlpha() / 2);
    }

    default void scaleAtPos(double posX, double posY, double scale) {
        SharedConstants.api.getGLStateManager().translate(posX, posY, 0);
        SharedConstants.api.getGLStateManager().scale(scale, scale, 1);
        SharedConstants.api.getGLStateManager().translate(-posX, -posY, 0);
    }

    default void rotateAtPos(double posX, double posY, float rotate) {
        SharedConstants.api.getGLStateManager().translate(posX, posY, 0);
        SharedConstants.api.getGLStateManager().rotate(rotate, 0, 0, 1);
        SharedConstants.api.getGLStateManager().translate(-posX, -posY, 0);
    }

    default double getWidth() {
        return RenderSystem.getWidth();
    }

    default double getHeight() {
        return RenderSystem.getHeight();
    }

    default int hexColor(int red, int green, int blue) {
        return RGBA.color(red, green, blue);
    }

    default int hexColor(int red, int green, int blue, int alpha) {
        return RGBA.color(red, green, blue, alpha);
    }

    default int hexColor(float r, float g, float b) {
        return RGBA.color(r, g, b);
    }

    default int hexColor(float r, float g, float b, float a) {
        return RGBA.color(r, g, b, a);
    }

    default int reAlpha(int color, float alpha) {
        if (alpha > 1) {
            alpha = 1;
        }

        if (alpha < 0) {
            alpha = 0;
        }
        return RGBA.color((color >> 16) & 0xFF, (color >> 8) & 0xFF, (color) & 0xFF, (int) (alpha * 255));
    }

    default boolean isHovered(double mouseX, double mouseY, double x, double y, double width, double height) {
        return RenderSystem.isHovered(mouseX, mouseY, x, y, width, height);
    }

}
