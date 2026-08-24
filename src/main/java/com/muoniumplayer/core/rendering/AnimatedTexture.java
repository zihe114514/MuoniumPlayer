package com.muoniumplayer.core.rendering;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.lwjgl.opengl.GL11;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.core.utils.timing.Timer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author IzumiiKonata
 * Date: 2025/11/17 22:43
 */
public class AnimatedTexture implements SharedConstants {

    int frameWidth;
    int imgHeight;

    List<Frame> frames = new ArrayList<>();
    boolean isAnimated = false;
    Location locImg;
    Timer timer = new Timer();
    int curFrame = 0;

    public AnimatedTexture(Location locImg) throws IOException {
        this(locImg, Location.of(locImg.getResourcePath() + ".mcmeta"));
    }

    AnimatedTexture(Location locImg, Location meta) throws IOException {
        this(DynamicTexture.readImage(locImg.getResourceStream()), meta.getResourceStream());
    }

    public AnimatedTexture(BufferedImage img, InputStream isMetadata) {
        this.frameWidth = img.getWidth();
        this.imgHeight = img.getHeight();

        CompletableFuture<Void> textureUploadingTask = MultiThreadingUtil.runAsync(() -> this.locImg = TextureManager.getInstance().getDynamicTextureLocation("AnimatedTexture", new DynamicTexture(img)));

        CompletableFuture<Void> serializeTask = MultiThreadingUtil.runAsync(() -> this.serializeMetadata(img, isMetadata));

        CompletableFuture
                .allOf(textureUploadingTask, serializeTask)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        ex.printStackTrace();
                    }
                });
    }

    public void render(double x, double y, double width, double height) {
        this.render(x, y, width, height, false);
    }

    public void render(double x, double y, double width, double height, boolean customColor) {

        if (this.locImg == null)
            return;

        Image.Type type = customColor ? Image.Type.NoColor : Image.Type.Normal;

        if (!isAnimated) {
            Image.drawNearest(this.locImg, x, y, width, height, type);
            return;
        }

        if (this.frames.isEmpty())
            return;

        Frame frame = this.frames.get(curFrame);
        if (frame.generated) {
            Image.drawNearest(frame.generatedLoc, x, y, width, height, type);
        } else {
            if (!customColor)
                api.getGLStateManager().color(1, 1, 1, 1);
            api.getGLStateManager().enableBlend();
            api.getGLStateManager().disableAlpha();
            api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            ITextureObject textureObj = TextureManager.getInstance().getTexture(locImg);
            TextureUtil.bindTexture(textureObj.getGlTextureId());
            RenderSystem.nearestFilter();

            int v = frame.origFrameIndex * frameWidth;

//            Tessellator tessellator = Tessellator.getInstance();
//            WorldRenderer worldrenderer = tessellator.getWorldRenderer();
//            worldrenderer.begin(7, DefaultVertexFormats.POSITION_TEX);
//            worldrenderer.pos(x,            y + height, 0.0D).tex(0, (double) (v + frameWidth) / imgHeight).endVertex();
//            worldrenderer.pos(x + width, y + height, 0.0D).tex(1, (double) (v + frameWidth) / imgHeight).endVertex();
//            worldrenderer.pos(x + width,    y, 0.0D)         .tex(1, (double) (v) / imgHeight).endVertex();
//            worldrenderer.pos(x,               y, 0.0D)         .tex(0, (double) (v) / imgHeight).endVertex();
//            tessellator.draw();

            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2d(0, (double) (v + frameWidth) / imgHeight);
            GL11.glVertex2d(x, y + height);
            GL11.glTexCoord2d(1, (double) (v + frameWidth) / imgHeight);
            GL11.glVertex2d(x + width, y + height);
            GL11.glTexCoord2d(1, (double) (v) / imgHeight);
            GL11.glVertex2d(x + width, y);
            GL11.glTexCoord2d(0, (double) (v) / imgHeight);
            GL11.glVertex2d(x, y);
            GL11.glEnd();

            api.getGLStateManager().enableAlpha();
        }

        if (timer.isDelayed((long) (frame.frameTime * 50))) {
            timer.reset();
            curFrame++;
            if (curFrame >= frames.size()) {
                curFrame = 0;
            }
        }

    }

    public static boolean metadataHasAnimationFrames(InputStream is) {
        // 资源里的 .mcmeta 是 UTF-8；不写死字符集时，中文 Windows 客户端会按 GBK 解码（开发环境是 UTF-8，测不出来）。
        JsonObject jObj = JsonUtils.toJsonObject(new InputStreamReader(is, StandardCharsets.UTF_8));

        if (!jObj.isJsonObject())
            return false;

        if (!jObj.has("animation")) {
            return false;
        }

        JsonElement animationElement = jObj.get("animation");

        if (!animationElement.isJsonObject()) {
            return false;
        }

        JsonObject animationObject = animationElement.getAsJsonObject();

        int frameTime = JsonUtils.getInt(animationObject, "frametime", 1);

        if (frameTime != 1) {
            if (frameTime < 1)
                frameTime = 1;
        }

        if (animationObject.has("frames")) {
            try {
                JsonArray framesArray = JsonUtils.getJsonArray(animationObject, "frames");

                for (int j = 0; j < framesArray.size(); ++j) {
                    JsonElement frameElement = framesArray.get(j);
                    Frame animationframe = parseAnimationFrame(j, frameTime, frameElement);

                    if (animationframe != null) {
                        return true;
                    }
                }
            } catch (ClassCastException classcastexception) {
                throw new JsonParseException("Invalid animation->frames: expected array, was " + animationObject.get("frames"), classcastexception);
            }
        }

        return false;
    }



    @SneakyThrows
    private void serializeMetadata(BufferedImage img, InputStream is) {
        if (is == null) {
            return;
        }

        JsonObject jObj = com.muoniumplayer.core.utils.json.JsonUtils.toJsonObject(
                new InputStreamReader(is, StandardCharsets.UTF_8));

        if (!jObj.isJsonObject())
            return;

        if (!jObj.has("animation")) {
            return;
        }

        JsonElement animationElement = jObj.get("animation");

        if (!animationElement.isJsonObject()) {
            return;
        }

        JsonObject animationObject = animationElement.getAsJsonObject();

        int frameTime = JsonUtils.getInt(animationObject, "frametime", 1);

        if (frameTime != 1) {
            if (frameTime < 1)
                frameTime = 1;
        }

        if (animationObject.has("frames")) {
            this.isAnimated = true;

            try {
                JsonArray framesArray = JsonUtils.getJsonArray(animationObject, "frames");

                for (int j = 0; j < framesArray.size(); ++j) {
                    JsonElement frameElement = framesArray.get(j);
                    Frame animationframe = parseAnimationFrame(j, frameTime, frameElement);

                    if (animationframe != null) {
                        frames.add(animationframe);
                    }
                }
            } catch (ClassCastException classcastexception) {
                throw new JsonParseException("Invalid animation->frames: expected array, was " + animationObject.get("frames"), classcastexception);
            }
        } else {
            this.isAnimated = true;

            int numFrames = img.getHeight() / img.getWidth();

            for (int i = 0; i < numFrames; ++i) {
                this.frames.add(new Frame(i, frameTime));
            }
        }

        boolean interpolate = JsonUtils.getBoolean(animationObject, "interpolate", false);
        if (interpolate) {
            this.generateInterpolatedFrames(img);
        }
    }

    private void generateInterpolatedFrames(BufferedImage image) {
        List<Frame> copy = new ArrayList<>(frames);
        this.frames.clear();

//        Graphics2D g2d = (Graphics2D) image.getGraphics();

        for (int i = 0; i < copy.size(); ++i) {
            Frame frame = copy.get(i);
            frame.frameTime *= 0.5;

            this.frames.add(frame);

            if (i < copy.size() - 1) {
                BufferedImage generated = new BufferedImage(image.getWidth(), image.getWidth(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = (Graphics2D) generated.getGraphics();

                g.setColor(new Color(255, 255, 255, 128));
                g.drawImage(
                        image,
                        0, 0,
                        image.getWidth(), image.getWidth(),
                        0, i * image.getWidth(),
                        image.getWidth(), i * image.getWidth() + image.getWidth(),
                        null
                );
                g.drawImage(
                        image,
                        0, 0,
                        image.getWidth(), image.getWidth(),
                        0, (i + 1) * image.getWidth(),
                        image.getWidth(), (i + 1) * image.getWidth() + image.getWidth(),
                        null
                );

                g.dispose();

                Frame gen = new Frame(-1, frame.frameTime);
                gen.generated = true;
                gen.generatedLoc = TextureManager.getInstance().getDynamicTextureLocation("ResourcePackPreviewGenerated", new DynamicTexture(generated));
                this.frames.add(gen);
            }
        }

        for (int i = 0; i < this.frames.size(); i++) {
            Frame frame = this.frames.get(i);
            frame.frameIndex = i;
        }
    }

    private static Frame parseAnimationFrame(int frameIndex, int fixedFrameTime, JsonElement frameElement) {
        if (frameElement.isJsonPrimitive()) {
            return new Frame(JsonUtils.getInt(frameElement, "frames[" + frameIndex + "]"), fixedFrameTime);
        } else if (frameElement.isJsonObject()) {
            JsonObject frameObject = JsonUtils.getJsonObject(frameElement, "frames[" + frameIndex + "]");
            int time = JsonUtils.getInt(frameObject, "time", -1);

            if (frameObject.has("time")) {
                if (time < 1)
                    time = 1;
            }

            int idx = JsonUtils.getInt(frameObject, "index");
            if (idx < 0)
                idx = 0;
            return new Frame(idx, time);
        } else {
            return null;
        }
    }



    private static class Frame {
        private int frameIndex;
        private int origFrameIndex;
        private double frameTime;

        @Getter
        @Setter
        private boolean generated = false;

        @Getter
        @Setter
        private Location generatedLoc;

        public Frame(int idx) {
            this(idx, -1);
        }

        public Frame(int idx, double time) {
            this.frameIndex = idx;
            this.origFrameIndex = idx;
            this.frameTime = time;
        }

        public boolean hasNoTime() {
            return this.frameTime == -1;
        }

        public double getFrameTime() {
            return this.frameTime;
        }

        public int getFrameIndex() {
            return this.frameIndex;
        }
    }
    
}
