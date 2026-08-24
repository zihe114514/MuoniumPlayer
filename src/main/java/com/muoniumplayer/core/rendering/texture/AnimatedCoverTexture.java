package com.muoniumplayer.core.rendering.texture;

import com.muoniumplayer.core.MuoniumPlayerExtension;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight animated image texture for remote dynamic album covers.
 * Frames are decoded off-thread and the texture advances only from the render
 * thread, keeping all OpenGL uploads on Minecraft's main thread.
 */
public final class AnimatedCoverTexture extends DynamicTexture {

    private final List<int[]> frames;
    private final List<Long> frameDurations;
    private int currentFrame;
    /** 当前是否在倒放。乒乓循环用一个方向标志表达，不需要把序列展开成两倍长度的帧表。 */
    private boolean rewinding;
    private long nextFrameAt;
    /** 换帧过程中的上传会再次绑定纹理,必须挡住由此产生的重入。 */
    private boolean uploadingFrame;

    public AnimatedCoverTexture(List<BufferedImage> sourceFrames, List<Long> sourceDurations) {
        // clearable 必须是 false:DynamicTexture 在首次上传后会把 dynamicTextureData 置空,而这里每换一帧
        // 都要往那个数组里拷像素再上传,置空之后第一次换帧就会 NPE。
        super(normalize(sourceFrames.get(0), sourceFrames.get(0).getWidth(), sourceFrames.get(0).getHeight()), false, true);
        int width = getWidth();
        int height = getHeight();
        this.frames = new ArrayList<int[]>(sourceFrames.size());
        this.frameDurations = new ArrayList<Long>(sourceFrames.size());
        for (int index = 0; index < sourceFrames.size(); index++) {
            BufferedImage frame = normalize(sourceFrames.get(index), width, height);
            int[] pixels = new int[width * height];
            frame.getRGB(0, 0, width, height, pixels, 0, width);
            this.frames.add(pixels);
            long duration = sourceDurations != null && index < sourceDurations.size()
                    ? sourceDurations.get(index) : 100L;
            this.frameDurations.add(Math.max(40L, Math.min(2000L, duration)));
        }
        this.nextFrameAt = System.currentTimeMillis() + this.frameDurations.get(0);
    }

    /** 帧数,1 表示实际上是静态图。 */
    public int getFrameCount() {
        return frames.size();
    }

    /** 帧数据占用的堆内存,用于日志与预算核对。 */
    public long getHeapBytes() {
        return (long) frames.size() * getWidth() * getHeight() * 4L;
    }

    @Override
    public int getGlTextureId() {
        advanceFrameIfDue();
        return super.getGlTextureId();
    }

    /**
     * 换帧只发生在渲染线程,并且必须挡住两种重入:
     *
     * <p>一是<b>构造期间</b>:父类构造函数里的 GL 分配会调用 {@code bindTexture() -> getGlTextureId()},
     * 虚方法派发到这里时 {@code frames} 还没有赋值,直接读它会 NPE——而这个异常发生在构造函数里,
     * 结果是动态封面纹理永远建不起来。</p>
     *
     * <p>二是<b>上传期间</b>:{@link DynamicTexture#updateDynamicTexture()} 自己会绑定纹理,于是又回到
     * {@code getGlTextureId()};若此时"该换帧"的条件仍然成立就会无限递归(StackOverflowError)。因此
     * 先推进 {@code nextFrameAt} 再上传,并额外用一个标志位兜住。</p>
     */
    private void advanceFrameIfDue() {
        List<int[]> localFrames = this.frames;
        List<Long> localDurations = this.frameDurations;
        if (localFrames == null || localDurations == null || localFrames.size() < 2
                || dynamicTextureData == null || uploadingFrame
                || !MuoniumPlayerExtension.isCallingFromMainThread()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextFrameAt) {
            return;
        }
        int size = localFrames.size();
        boolean nextRewinding = nextRewinding(currentFrame, size, rewinding);
        // nextRewinding 的契约保证这一步不会越界：往回走时 currentFrame > 0，往前走时 currentFrame + 1 < size。
        int next = nextRewinding ? currentFrame - 1 : currentFrame + 1;
        if (next < 0 || next >= size) {
            // 只可能来自不该发生的状态；从头正放而不是抛异常，动态封面不值得为此断掉。
            next = 0;
            nextRewinding = false;
        }
        int[] pixels = localFrames.get(next);
        if (pixels.length != dynamicTextureData.length) {
            return;
        }
        currentFrame = next;
        rewinding = nextRewinding;
        nextFrameAt = now + localDurations.get(next);
        System.arraycopy(pixels, 0, dynamicTextureData, 0, dynamicTextureData.length);
        uploadingFrame = true;
        try {
            updateDynamicTexture();
        } finally {
            uploadingFrame = false;
        }
    }

    /**
     * 乒乓循环里下一步的方向：{@code true} 表示接下来往回走。
     *
     * <p>播放顺序是 {@code 0,1,…,N-1,N-2,…,1,0,1,…}，两个端点各只出现一次。朴素写法容易在端点
     * 把同一帧连播两次（{@code …,N-1,N-1,…}），看起来是动画卡了一下，所以方向在<b>到达</b>端点的
     * 那一步就翻转，而不是等到试图越过端点时才翻。</p>
     *
     * <p>调用方据此直接 ±1 取下一帧，因此这里必须保证：返回 {@code true} 时 {@code current > 0}，
     * 返回 {@code false} 时 {@code current + 1 < size}。{@code size >= 2} 是前提，单帧纹理根本不会
     * 走到换帧逻辑。</p>
     */
    static boolean nextRewinding(int current, int size, boolean rewinding) {
        if (size < 2) return false;
        if (current <= 0) return false;
        if (current >= size - 1) return true;
        return rewinding;
    }

    private static BufferedImage normalize(BufferedImage source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height && source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }
}
