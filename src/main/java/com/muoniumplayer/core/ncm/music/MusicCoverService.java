package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Cleanup;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.apache.commons.io.IOUtils;
import com.muoniumplayer.core.rendering.GaussianKernel;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.texture.AbstractTexture;
import com.muoniumplayer.core.rendering.texture.AnimatedCoverTexture;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.network.HttpUtils;
import com.muoniumplayer.core.utils.other.multithreading.BackgroundWork;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import com.muoniumplayer.core.settings.HudConfig;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Node;

/**
 * Asynchronous music-cover retrieval, texture upload and lyric-background blur generation.
 *
 * <p>This service intentionally preserves the existing asynchronous task boundaries: the main
 * cover texture is uploaded as soon as it downloads, while the blurred lyric texture is produced
 * on a separate task.</p>
 */
final class MusicCoverService {

    private static final Kernel GAUSSIAN_KERNEL = new Kernel(41, 41, GaussianKernel.generate(41));
    private static final int MAX_DYNAMIC_COVER_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DYNAMIC_COVER_DIMENSION = 512;
    private static final int MAX_DYNAMIC_COVER_FRAMES = 24;
    /** 动态封面视频的下载上限。网易云的封面循环一般 1–3 MB,12 MB 足够而且不会让一次失手吃满内存。 */
    private static final int MAX_DYNAMIC_COVER_VIDEO_BYTES = 12 * 1024 * 1024;
    private static final Set<String> DYNAMIC_COVER_ATTEMPTS = new HashSet<String>();
    /** 尝试记录只用于去重,不能无限增长;超过这个数量就整表清空重新来。 */
    private static final int MAX_DYNAMIC_COVER_ATTEMPTS = 512;
    /**
     * 已装载的动态封面纹理。TextureManager 自己从不淘汰,而动画封面的帧数据是十几 MB 的堆内存 +
     * 一张 GL 纹理,不主动回收的话每播一首歌就多留一份。只保留当前与上一首(全屏歌词页切歌时会同时
     * 用到两首的封面做交叉淡化)。
     */
    private static final int MAX_LIVE_DYNAMIC_COVERS = 2;
    private static final LinkedList<LiveDynamicCover> LIVE_DYNAMIC_COVERS = new LinkedList<LiveDynamicCover>();

    private MusicCoverService() {
    }

    static void loadMusicCover(Music music, boolean forceReload) {
        Location musicCover = music.getCoverLocation();
        Location musicCoverSmall = music.getSmallCoverLocation();
        Location musicCoverBlur = music.getBlurredCoverLocation();
        TextureManager textureManager = TextureManager.getInstance();

        if (shouldLoadCover(textureManager, musicCover, forceReload)) {
            loadMainCoverAsync(music, musicCover, musicCoverBlur);
        }
        if (shouldLoadCover(textureManager, musicCoverSmall, forceReload)) {
            loadSmallCoverAsync(music, musicCoverSmall);
        }
    }

    /**
     * 为当前播放的网易云曲目取动态封面。
     *
     * <p>网易云的动态封面实际上是一段几秒的循环 <b>MP4</b>(接口字段 {@code videoPlayUrl}),因此这里有
     * 两条路:极少数返回 GIF/APNG 之类的图片时直接用 ImageIO 解;返回视频时交给 {@link VideoCoverFrames}
     * 用 ffmpeg 抽成有限帧,再包成项目已有的 {@link AnimatedCoverTexture} 循环播放。</p>
     *
     * <p>只为"正在播放"的那一首请求:动态封面是给桌面歌曲信息 HUD、全屏歌词页和播放条上的当前曲目用的,
     * 歌单列表里的缩略图仍然是静态封面(几十条同时解视频既没必要也扛不住)。</p>
     *
     * <p>任何一步失败(未登录、这首歌没有动态封面、ffmpeg 不存在、视频损坏、超时)都只是保持静态封面,
     * 不影响播放,也不会重试到打爆接口。</p>
     */
    static void loadDynamicMusicCover(final Music music) {
        if (music == null || !music.isNetease() || music.getId() <= 0L) {
            return;
        }
        if (!isDynamicCoverEnabled()) {
            return;
        }
        final Location dynamicLocation = music.getDynamicCoverLocation();
        final TextureManager textureManager = TextureManager.getInstance();
        if (textureManager.getTexture(dynamicLocation) != null) {
            return;
        }
        if (!markAttempted(music)) {
            return;
        }

        // 低优先级池：这条链要下载一段视频再用 ffmpeg 抽成几十张 256×256 的 PNG，是纯装饰性开销，
        // 没有任何理由和渲染线程争 CPU。
        BackgroundWork.submit(() -> {
            try {
                JsonObject response = CloudMusicApi.songDynamicCover(music.getId()).toJsonObject();
                String imageUrl = findDynamicCoverUrl(response);
                String videoUrl = findDynamicVideoUrl(response);
                if (imageUrl.isEmpty() && videoUrl.isEmpty()) {
                    // 绝大多数曲目本来就没有动态封面(接口返回 data:{}),这不是失败,但日志里必须能和
                    // "取到了却用不上"区分开,否则无法判断是曲目问题还是功能坏了。
                    System.out.println("[Music/Cover] No dynamic cover published for " + music.getStableKey());
                    return;
                }
                ITextureObject texture = decodeImageCover(imageUrl);
                if (!isAnimated(texture)) {
                    // 图片分支拿到的是单帧(等于静态封面)或什么都没拿到,这时视频才是真正的动态封面。
                    ITextureObject video = decodeVideoCover(music, videoUrl);
                    if (video != null) {
                        discard(texture);
                        texture = video;
                    }
                }
                if (texture == null) {
                    return;
                }
                install(music, dynamicLocation, texture);
            } catch (Throwable failure) {
                // 动态封面纯属装饰,静态封面始终是保底;但静默失败会让问题无法定位,所以留一行。
                System.err.println("[Music/Cover] Dynamic cover lookup failed for " + music.getStableKey()
                        + ": " + failure);
            }
        });
    }

    /** 动态封面已经就绪时返回它,否则返回给定的静态封面。渲染层用这个决定绑哪张纹理。 */
    static Location preferredCoverLocation(Music music, Location fallback) {
        if (music == null) {
            return fallback;
        }
        Location dynamic = music.getDynamicCoverLocation();
        return TextureManager.getInstance().getTexture(dynamic) != null ? dynamic : fallback;
    }

    /**
     * 开关的唯一真实来源是 {@link HudConfig#animatedCoverEnabled}:HUD 编辑器的"动态封面"行与模块里的
     * {@code Animated Cover} 都读写它,两处显示因此永远一致,而且不依赖外部值存储是否已初始化。
     */
    private static boolean isDynamicCoverEnabled() {
        return HudConfig.animatedCoverEnabled;
    }

    /** ffmpeg 的已缓存探测结论:1 可用,-1 探测过但没找到,0 还没探测。不会触发新的探测。 */
    static int ffmpegState() {
        return FfmpegSupport.cachedState();
    }

    /** 同一首歌只尝试一次,避免每次重新播放都打一遍接口。 */
    private static boolean markAttempted(Music music) {
        synchronized (DYNAMIC_COVER_ATTEMPTS) {
            if (DYNAMIC_COVER_ATTEMPTS.size() >= MAX_DYNAMIC_COVER_ATTEMPTS) {
                DYNAMIC_COVER_ATTEMPTS.clear();
            }
            return DYNAMIC_COVER_ATTEMPTS.add(music.getStableKey());
        }
    }

    /** 撤销尝试记录。只有"这次没成是环境问题"时才用,例如用户还没装 ffmpeg。 */
    private static void forgetAttempt(Music music) {
        synchronized (DYNAMIC_COVER_ATTEMPTS) {
            DYNAMIC_COVER_ATTEMPTS.remove(music.getStableKey());
        }
    }

    /** 图片型动态封面(GIF/APNG)。没有图片 URL 或解不出来时返回 null,交给视频分支。 */
    private static ITextureObject decodeImageCover(String imageUrl) {
        try {
            if (imageUrl == null || imageUrl.isEmpty() || isVideoUrl(imageUrl)) {
                return null;
            }
            byte[] imageBytes;
            try (InputStream stream = HttpUtils.downloadStream(imageUrl, 2)) {
                imageBytes = readAtMost(stream, MAX_DYNAMIC_COVER_BYTES);
            }
            return decodeDynamicCover(imageBytes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 视频型动态封面:下载到临时文件后用 ffmpeg 抽帧。ffmpeg 不存在时提示一次并撤销尝试记录——用户可能
     * 稍后才装上,下次播到这首歌应该还能再试。
     */
    private static ITextureObject decodeVideoCover(Music music, String videoUrl) {
        if (videoUrl == null || videoUrl.isEmpty()) {
            return null;
        }
        if (!FfmpegSupport.isAvailable()) {
            FfmpegSupport.warnMissingOnce();
            forgetAttempt(music);
            return null;
        }

        File temporary = null;
        try {
            temporary = File.createTempFile("muonium-cover-", ".mp4");
            long written;
            try (InputStream stream = HttpUtils.downloadStream(videoUrl, 2)) {
                written = writeAtMost(stream, temporary, MAX_DYNAMIC_COVER_VIDEO_BYTES);
            }
            if (written <= 0L) {
                return null;
            }

            List<BufferedImage> frames = VideoCoverFrames.decode(temporary);
            if (frames.isEmpty()) {
                return null;
            }
            List<Long> durations = new ArrayList<Long>(frames.size());
            for (int index = 0; index < frames.size(); index++) {
                durations.add(VideoCoverFrames.frameDurationMillis());
            }
            AnimatedCoverTexture texture = new AnimatedCoverTexture(frames, durations);
            System.out.println("[Music/Cover] Animated cover ready for " + music.getStableKey() + ": "
                    + frames.size() + " frames, " + (texture.getHeapBytes() / (1024L * 1024L)) + " MB");
            return texture;
        } catch (Throwable failure) {
            System.err.println("[Music/Cover] Animated cover unavailable for " + music.getStableKey() + ": "
                    + failure);
            return null;
        } finally {
            if (temporary != null && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }

    /**
     * 装载纹理。解码期间用户可能已经换歌了,这种情况下必须把已经分配的 GL 纹理删掉而不是留着——它不在
     * TextureManager 里,没人再有机会回收它。
     */
    private static void install(final Music music, final Location dynamicLocation, final ITextureObject texture) {
        final String cacheKey = music.getStableKey();
        MultiThreadingUtil.runOnMainThread(() -> {
            // 比 stableKey 而不是比引用:歌单重载、automix 交接都可能让"同一首歌"换成另一个 Music 实例,
            // 而纹理位置本来就是按 stableKey 生成的。
            Music playing = CloudMusic.currentlyPlaying;
            if (playing == null || !cacheKey.equals(playing.getStableKey())) {
                // 解码期间换歌了:纹理没进 TextureManager,不删就再没人有机会删。
                discard(texture);
                System.out.println("[Music/Cover] Dropped dynamic cover for " + cacheKey + ": track changed");
                return;
            }
            TextureManager.getInstance().loadTexture(dynamicLocation, texture);
            trimLiveDynamicCovers(new LiveDynamicCover(cacheKey, dynamicLocation));
        });
    }

    /**
     * 主线程上执行:新装一张,顺手把过老的动态封面连纹理一起丢掉。被淘汰的那首同时撤销尝试记录——
     * 纹理已经没了,以后再播到它应该允许重新取一次,否则只能看静态封面。
     */
    private static void trimLiveDynamicCovers(LiveDynamicCover installed) {
        for (java.util.Iterator<LiveDynamicCover> iterator = LIVE_DYNAMIC_COVERS.iterator(); iterator.hasNext(); ) {
            if (iterator.next().location.equals(installed.location)) iterator.remove();
        }
        LIVE_DYNAMIC_COVERS.addFirst(installed);
        while (LIVE_DYNAMIC_COVERS.size() > MAX_LIVE_DYNAMIC_COVERS) {
            LiveDynamicCover stale = LIVE_DYNAMIC_COVERS.removeLast();
            TextureManager.getInstance().deleteTexture(stale.location);
            synchronized (DYNAMIC_COVER_ATTEMPTS) {
                DYNAMIC_COVER_ATTEMPTS.remove(stale.key);
            }
        }
    }

    /** 真的是动画(至少两帧)才算动态封面;单帧结果和静态封面没有区别。 */
    private static boolean isAnimated(ITextureObject texture) {
        return texture instanceof AnimatedCoverTexture && ((AnimatedCoverTexture) texture).getFrameCount() > 1;
    }

    private static void discard(ITextureObject texture) {
        try {
            if (texture instanceof AbstractTexture) {
                ((AbstractTexture) texture).deleteGlTexture();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 视频 URL 专用的候选选择。与图片分支相反:这里只要视频。 */
    private static String findDynamicVideoUrl(JsonElement element) {
        List<DynamicUrlCandidate> candidates = new ArrayList<DynamicUrlCandidate>();
        collectUrlCandidates(element, "", candidates);
        DynamicUrlCandidate best = null;
        for (DynamicUrlCandidate candidate : candidates) {
            if (!isVideoUrl(candidate.url)) {
                continue;
            }
            if (best == null || candidate.score > best.score) {
                best = candidate;
            }
        }
        return best == null ? "" : best.url;
    }

    /** 按上限写盘,超限直接放弃(返回 0)而不是留半个文件让 ffmpeg 去猜。 */
    private static long writeAtMost(InputStream stream, File target, long maxBytes) throws IOException {
        if (stream == null) {
            return 0L;
        }
        long total = 0L;
        try (OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    return 0L;
                }
                output.write(buffer, 0, read);
            }
        }
        return total;
    }

    static BufferedImage gaussianBlur(BufferedImage image, int blur) {
        Map<RenderingHints.Key, Object> map = new HashMap<>();
        map.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        map.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        map.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        RenderingHints hints = new RenderingHints(map);

        ConvolveOp operation = new ConvolveOp(GAUSSIAN_KERNEL, ConvolveOp.EDGE_NO_OP, hints);
        BufferedImage filtered = operation.filter(image, null);
        BufferedImage output = new BufferedImage(filtered.getWidth(), filtered.getHeight(), filtered.getType());
        Graphics2D graphics = (Graphics2D) output.getGraphics();
        graphics.setRenderingHints(map);
        graphics.drawImage(filtered, -blur, -blur, filtered.getWidth() + blur * 2,
                filtered.getHeight() + blur * 2, null);
        return output;
    }

    private static boolean shouldLoadCover(TextureManager textureManager, Location coverLocation,
                                           boolean forceReload) {
        return textureManager.getTexture(coverLocation) == null || forceReload;
    }

    /** Resolves a GD track cover on the background thread; official tracks return as-is. */
    private static String coverUrl(Music music, int size) {
        if (music != null && music.isGd()) {
            return music.resolveGdCoverUrl(size);
        }
        return music == null ? "" : music.getCoverUrl(size);
    }

    private static void loadMainCoverAsync(Music music, Location musicCover, Location musicCoverBlur) {
        MultiThreadingUtil.runAsync(() -> {
            try {
                @Cleanup
                InputStream coverStream = HttpUtils.downloadStream(coverUrl(music, 320), 5);
                byte[] imageData = IOUtils.toByteArray(coverStream);
                BufferedImage coverImage = DynamicTexture.readImage(new ByteArrayInputStream(imageData));
                if (coverImage != null) {
                    loadCoverTextures(coverImage, musicCover, musicCoverBlur);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private static void loadCoverTextures(BufferedImage coverImage, Location musicCover, Location musicCoverBlur) {
        Textures.loadTexture(musicCover, coverImage);
        // 低优先级池：31 半径的高斯模糊是纯 CPU 卷积，一张 320×320 的封面就能吃满一个核几十毫秒。
        BackgroundWork.submit(() -> {
            BufferedImage inputImage = new BufferedImage(coverImage.getWidth(), coverImage.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            inputImage.setRGB(0, 0, coverImage.getWidth(), coverImage.getHeight(),
                    coverImage.getRGB(0, 0, coverImage.getWidth(), coverImage.getHeight(), null, 0,
                            coverImage.getWidth()), 0, coverImage.getWidth());
            Textures.loadTexture(musicCoverBlur, gaussianBlur(inputImage, 31));
        });
    }

    private static void loadSmallCoverAsync(Music music, Location musicCoverSmall) {
        MultiThreadingUtil.runAsync(() -> {
            InputStream smallCoverStream = HttpUtils.downloadStream(coverUrl(music, 128), 5);
            BufferedImage smallCoverImage = DynamicTexture.readImage(smallCoverStream);
            Textures.loadTexture(musicCoverSmall, smallCoverImage);
        });
    }

    private static ITextureObject decodeDynamicCover(byte[] imageBytes) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                int count;
                try {
                    count = Math.min(MAX_DYNAMIC_COVER_FRAMES, Math.max(1, reader.getNumImages(true)));
                } catch (Throwable ignored) {
                    count = 1;
                }
                List<BufferedImage> frames = new ArrayList<BufferedImage>(count);
                List<Long> durations = new ArrayList<Long>(count);
                for (int index = 0; index < count; index++) {
                    BufferedImage frame = reader.read(index);
                    if (frame == null) {
                        continue;
                    }
                    frames.add(scaleDown(frame));
                    durations.add(readFrameDuration(reader.getImageMetadata(index)));
                }
                if (frames.isEmpty()) {
                    return null;
                }
                return frames.size() > 1 ? new AnimatedCoverTexture(frames, durations)
                        : new DynamicTexture(frames.get(0), true, true);
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage scaleDown(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int max = Math.max(width, height);
        if (max <= MAX_DYNAMIC_COVER_DIMENSION && source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        double scale = max <= MAX_DYNAMIC_COVER_DIMENSION ? 1.0 : MAX_DYNAMIC_COVER_DIMENSION / (double) max;
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage result = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static long readFrameDuration(IIOMetadata metadata) {
        if (metadata == null) {
            return 100L;
        }
        try {
            Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
            Node child = root.getFirstChild();
            while (child != null) {
                if ("GraphicControlExtension".equals(child.getNodeName()) && child.getAttributes() != null
                        && child.getAttributes().getNamedItem("delayTime") != null) {
                    return Math.max(40L, Long.parseLong(child.getAttributes().getNamedItem("delayTime").getNodeValue()) * 10L);
                }
                child = child.getNextSibling();
            }
        } catch (Throwable ignored) {
            // Non-GIF image readers do not expose the GIF metadata tree.
        }
        return 100L;
    }

    private static byte[] readAtMost(InputStream stream, int maxBytes) throws IOException {
        if (stream == null) {
            return new byte[0];
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("dynamic cover exceeds size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String findDynamicCoverUrl(JsonElement element) {
        List<DynamicUrlCandidate> candidates = new ArrayList<DynamicUrlCandidate>();
        collectUrlCandidates(element, "", candidates);
        DynamicUrlCandidate best = null;
        for (DynamicUrlCandidate candidate : candidates) {
            if (isVideoUrl(candidate.url)) {
                continue;
            }
            if (best == null || candidate.score > best.score) {
                best = candidate;
            }
        }
        return best == null ? "" : best.url;
    }

    private static void collectUrlCandidates(JsonElement element, String path, List<DynamicUrlCandidate> candidates) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString();
            if (value.startsWith("http://") || value.startsWith("https://")) {
                String lowerPath = path.toLowerCase();
                int score = 1;
                if (lowerPath.contains("dynamic")) score += 100;
                if (lowerPath.contains("cover")) score += 40;
                if (lowerPath.contains("image") || lowerPath.contains("pic")) score += 25;
                if (lowerPath.contains("url")) score += 10;
                candidates.add(new DynamicUrlCandidate(value, score));
            }
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                collectUrlCandidates(array.get(index), path + "[]", candidates);
            }
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                collectUrlCandidates(entry.getValue(), path + "." + entry.getKey(), candidates);
            }
        }
    }

    private static boolean isVideoUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase();
        return lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".flv") || lower.contains(".webm");
    }

    /** 已装载的动态封面:位置用于删纹理,stableKey 用于撤销尝试记录。 */
    private static final class LiveDynamicCover {
        private final String key;
        private final Location location;

        private LiveDynamicCover(String key, Location location) {
            this.key = key;
            this.location = location;
        }
    }

    private static final class DynamicUrlCandidate {
        private final String url;
        private final int score;

        private DynamicUrlCandidate(String url, int score) {
            this.url = url;
            this.score = score;
        }
    }
}
