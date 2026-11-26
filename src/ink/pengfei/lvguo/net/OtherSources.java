package ink.pengfei.lvguo.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ink.pengfei.lvguo.model.Episode;
import ink.pengfei.lvguo.model.Gear;
import ink.pengfei.lvguo.model.Series;

/**
 * 其它片源（照 GuoDuo iOS 新版 core/upstream/xifan.js、hema.js）：
 *  - 西饭：xifan-api-cn.youlishipin.com 公开接口（列表 / 详情 / 播放地址都是 JSON）
 *  - 河马：www.kuaikaw.cn 网页里的 __NEXT_DATA__（列表 / 详情；前几集免费，收费集不放）
 * 剧集 id 带前缀：xifan:{source}:{duanjuId} / hema:{bookId}；分集 vid = 剧集 id + ":" + 分集 id。
 * 播放地址是普通 https mp4（不加密），交给同一套 Media3 播放器。
 */
public final class OtherSources {
    private OtherSources() {}

    public static final String XIFAN = "xifan", HEMA = "hema";
    private static final String XF = "https://xifan-api-cn.youlishipin.com";
    private static final String HM = "https://www.kuaikaw.cn";
    private static final Pattern NEXT = Pattern.compile("<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE);

    public static boolean is(String id) { return id != null && (id.startsWith("xifan:") || id.startsWith("hema:")); }
    public static String sourceOf(String id) { return id == null ? "" : id.startsWith("xifan:") ? XIFAN : id.startsWith("hema:") ? HEMA : ""; }
    public static String label(String source) { return XIFAN.equals(source) ? "西饭" : HEMA.equals(source) ? "河马" : "红果"; }

    // ------------------------------------------------------------ 列表

    public static final class Genre { public final String slug, label; final int id, version; Genre(String s, String l, int i, int v) { slug = s; label = l; id = i; version = v; } }
    public static final class Page { public final List<Series> items = new ArrayList<>(); public boolean more; public int next; public List<Genre> genres = new ArrayList<>(); }

    private static final Map<String, Object[]> GENRES = new HashMap<>();

    /** 题材列表（5 分钟缓存）。 */
    public static synchronized List<Genre> genres(String source) throws Exception {
        Object[] hit = GENRES.get(source);
        if (hit != null && (Long) hit[0] > System.currentTimeMillis()) { @SuppressWarnings("unchecked") List<Genre> g = (List<Genre>) hit[1]; return g; }
        List<Genre> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (XIFAN.equals(source)) {
            JSONObject r = xf("/xifan/drama/portalPage", "reqType=duanjuCategory");
            for (JSONObject c : contents(r)) {
                JSONObject v = c.optJSONObject("categoryItemVo"); if (v == null) continue;
                JSONArray subs = v.optJSONArray("subCategories");
                List<JSONObject> list = new ArrayList<>();
                if (subs != null) for (int i = 0; i < subs.length(); i++) list.add(subs.optJSONObject(i)); else list.add(v);
                for (JSONObject s : list) {
                    if (s == null) continue;
                    int id = s.optInt("categoryId"); String label = s.optString("oppoCategory").trim();
                    if (id <= 0 || label.isEmpty() || !seen.add(label)) continue;
                    out.add(new Genre(id + "-" + label, label, id, Math.max(1, s.optInt("version", 1))));
                }
            }
        } else {
            JSONObject d = hmPage("/browse");
            JSONArray t = d.optJSONArray("typeThreeList");
            if (t != null) for (int i = 0; i < t.length(); i++) {
                JSONObject x = t.optJSONObject(i); if (x == null) continue;
                JSONArray ids = x.optJSONArray("typeThreeIds"); String label = x.optString("name").trim();
                if (ids == null || ids.length() == 0 || label.isEmpty()) continue;
                StringBuilder slug = new StringBuilder();
                for (int k = 0; k < ids.length(); k++) { if (k > 0) slug.append('-'); slug.append(ids.optString(k)); }
                if ("0".contentEquals(slug) || !seen.add(slug.toString())) continue;
                out.add(new Genre(slug.toString(), label, 0, 0));
            }
        }
        if (out.isEmpty()) throw new java.io.IOException(label(source) + "没有返回题材");
        GENRES.put(source, new Object[]{System.currentTimeMillis() + 300_000L, out});
        return out;
    }

    /** 某个片源的一页剧（genre 为空 = 西饭「都市」/ 河马「全部」）。page 从 1 开始。 */
    public static Page browse(String source, String genre, int page) throws Exception {
        Page out = new Page();
        int pg = Math.max(1, page);
        if (XIFAN.equals(source)) {
            List<Genre> gs = genres(source);
            Genre g = null;
            for (Genre x : gs) if (genre != null && !genre.isEmpty() ? x.slug.equals(genre) : x.label.equals("都市")) { g = x; break; }
            if (g == null) g = gs.get(0);
            JSONObject r = xf("/xifan/drama/portalPage", "reqType=aggregationPage&offset=" + (pg - 1) * 30 + "&categoryId=" + g.id
                    + "&categoryNames=" + enc(g.label) + "&categoryVersion=" + g.version);
            for (JSONObject c : contents(r)) { Series s = xfSeries(c.optJSONObject("duanjuVo")); if (s != null) out.items.add(s); }
            out.more = r.optBoolean("hasMore") && !out.items.isEmpty();
        } else {
            String slug = genre == null || genre.isEmpty() ? "0" : genre;
            String path = "0".equals(slug) && pg == 1 ? "/browse" : "/browse/" + slug + (pg > 1 ? "/" + pg : "");
            String html = get(HM + path, true);
            JSONObject d = nextData(html);
            JSONArray books = d.optJSONArray("bookList");
            if (books != null) for (int i = 0; i < books.length(); i++) { Series s = hmSeries(books.optJSONObject(i)); if (s != null) out.items.add(s); }
            out.more = !out.items.isEmpty() && html.contains("href=\"/browse/" + slug + "/" + (pg + 1) + "\"");
        }
        out.next = pg + 1;
        out.genres = genres(source);
        return out;
    }

    public static Page search(String source, String keyword, int page) throws Exception {
        Page out = new Page();
        int pg = Math.max(1, page);
        if (XIFAN.equals(source)) {
            JSONObject r = xf("/xifan/search/getSearchList", "keyword=" + enc(keyword) + "&pageIndex=" + pg);
            for (JSONObject c : contents(r)) { Series s = xfSeries(c.optJSONObject("duanjuVo")); if (s != null) out.items.add(s); }
            out.more = r.optBoolean("hasMore") && !out.items.isEmpty();
        } else {
            JSONObject body = new JSONObject().put("sourceType", 1).put("keyword", keyword).put("index", pg);
            JSONObject r = new JSONObject(post(HM + "/seo/video/6007", body.toString()));
            JSONObject d = r.optJSONObject("data");
            JSONArray books = d == null ? null : d.optJSONArray("bookList");
            if (books != null) for (int i = 0; i < books.length(); i++) { Series s = hmSeries(books.optJSONObject(i)); if (s != null) out.items.add(s); }
            out.more = d != null && d.optInt("isMore") == 1 && !out.items.isEmpty();
        }
        out.next = pg + 1;
        return out;
    }

    private static Series xfSeries(JSONObject v) {
        if (v == null) return null;
        String src = v.optString("source"), id = v.optString("duanjuId"), title = plain(v.optString("title"));
        if (src.isEmpty() || id.isEmpty() || title.isEmpty()) return null;
        Series s = new Series();
        s.seriesId = "xifan:" + src + ":" + id; s.title = title; s.cover = https(v.optString("coverImageUrl"));
        s.episodeCount = v.optInt("total"); s.intro = plain(v.optString("desc"));
        JSONArray cats = v.optJSONArray("categories");
        if (cats != null) { List<String> c = new ArrayList<>(); for (int i = 0; i < cats.length() && c.size() < 4; i++) { String x = plain(cats.optString(i)); if (!x.isEmpty() && !x.contains("集")) c.add(x); } s.category = android.text.TextUtils.join(",", c); }
        s.playCount = heat(v.optString("playViewText"));
        JSONObject cur = v.optJSONObject("currentEpisode");
        if (cur != null && !cur.optString("episodeId").isEmpty()) s.firstVid = s.seriesId + ":" + cur.optString("episodeId");
        return s;
    }

    private static Series hmSeries(JSONObject b) {
        if (b == null) return null;
        String id = b.optString("bookId"), title = plain(b.optString("bookName"));
        if (!id.matches("\\d{1,30}") || title.isEmpty()) return null;
        Series s = new Series();
        s.seriesId = "hema:" + id; s.title = title; s.cover = https(b.optString("coverWap"));
        try { s.episodeCount = Integer.parseInt(b.optString("totalChapterNum", "0")); } catch (NumberFormatException ignored) { }
        s.intro = plain(b.optString("introduction"));
        JSONArray tags = b.optJSONArray("bookTypeThree");
        if (tags != null) { List<String> c = new ArrayList<>(); for (int i = 0; i < tags.length() && c.size() < 4; i++) { JSONObject t = tags.optJSONObject(i); if (t != null && !t.optString("name").isEmpty()) c.add(t.optString("name")); } s.category = android.text.TextUtils.join(",", c); }
        return s;
    }

    // ------------------------------------------------------------ 详情 / 播放

    /** 详情 + 分集（只列可免费看的分集；锁定的不进列表，避免点了播不了）。 */
    public static Repo.Detail detail(String seriesId) throws Exception {
        Repo.Detail d = new Repo.Detail();
        if (seriesId.startsWith("xifan:")) {
            String[] p = seriesId.split(":");
            if (p.length != 3) throw new java.io.IOException("西饭剧集编号无效");
            JSONObject r = xf("/xifan/drama/getDuanjuInfo", "source=" + enc(p[1]) + "&duanjuId=" + enc(p[2]));
            d.title = plain(r.optString("title")); d.cover = https(r.optString("coverImageUrl")); d.intro = plain(r.optString("desc"));
            d.playCount = heat(r.optString("playViewText")); d.payType = 0;
            JSONArray eps = r.optJSONArray("episodeList");
            if (eps != null) for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.optJSONObject(i); if (e == null || !e.optBoolean("unlock")) continue;
                Episode ep = new Episode();
                ep.index = e.optInt("index", i + 1); ep.vid = seriesId + ":" + e.optString("episodeId");
                ep.title = plain(e.optString("title")); ep.durationSec = (int) e.optDouble("videoDuration", 0); ep.cover = https(e.optString("coverImageUrl"));
                d.episodes.add(ep);
                PLAY.put(ep.vid, new String[]{https(e.optString("playUrl")), String.valueOf(e.optInt("videoWidth")), String.valueOf(e.optInt("videoHeight")), String.valueOf(e.optLong("videoSize"))});
            }
        } else if (seriesId.startsWith("hema:")) {
            String book = seriesId.substring(5);
            JSONObject r = nextData(get(HM + "/drama/" + book, true));
            JSONObject info = r.optJSONObject("bookInfoVo");
            if (info == null || !book.equals(info.optString("bookId"))) throw new java.io.IOException("河马返回的剧集与请求不一致");
            d.title = plain(info.optString("bookName")); d.cover = https(info.optString("coverWap")); d.intro = plain(info.optString("introduction"));
            d.payType = 0;
            JSONArray ch = r.optJSONArray("chapterList");
            if (ch != null) for (int i = 0; i < ch.length(); i++) {
                JSONObject e = ch.optJSONObject(i); if (e == null || !"0".equals(e.optString("isCharge"))) continue;
                JSONObject v = e.optJSONObject("chapterVideoVo");
                String url = v == null ? "" : https(v.optString("mp4720p")); if (url.isEmpty() && v != null) url = https(v.optString("mp4"));
                if (url.isEmpty()) continue;
                Episode ep = new Episode();
                ep.index = e.optInt("chapterIndex", i + 1); ep.vid = seriesId + ":" + e.optString("chapterId");
                ep.title = plain(e.optString("chapterName"));
                d.episodes.add(ep);
                PLAY.put(ep.vid, new String[]{url, "720", "1280", "0"});
            }
        } else throw new IllegalArgumentException("不是其它片源");
        d.episodeCount = d.episodes.size();
        if (d.title == null || d.title.isEmpty()) d.title = "未命名短剧";
        return d;
    }

    /** vid → {url, w, h, size}，由 detail() 填。 */
    private static final Map<String, String[]> PLAY = java.util.Collections.synchronizedMap(new HashMap<>());

    /** 播放档位：一个普通 mp4（不加密）。 */
    public static List<Gear> play(String vid) throws Exception {
        String[] hit = PLAY.get(vid);
        if (hit == null) {
            int cut = vid.lastIndexOf(':');
            detail(vid.substring(0, cut));
            hit = PLAY.get(vid);
        }
        if (hit == null || hit[0].isEmpty()) throw new java.io.IOException("这一集需要在官方 App 解锁");
        String url = hit[0];
        // 西饭的地址是 302 跳转：先解析到真实 mp4（StreamingSource 按 Range 分段拉）
        if (url.contains("youlishipin.com")) url = resolve(url);
        Gear g = new Gear();
        g.definition = "720p"; g.codec = "h264";
        try { g.width = Integer.parseInt(hit[1]); g.height = Integer.parseInt(hit[2]); g.size = Long.parseLong(hit[3]); } catch (NumberFormatException ignored) { }
        if (g.width <= 0 || g.height <= 0) { g.width = 720; g.height = 1280; }
        g.mainUrl = url;
        List<Gear> out = new ArrayList<>(); out.add(g);
        return out;
    }

    // ------------------------------------------------------------ 网络

    private static JSONObject xf(String path, String query) throws Exception {
        JSONObject j = new JSONObject(get(XF + path + "?" + query + "&version=2001001&androidVersionCode=28", false));
        if (j.optInt("ret", -1) != 0 || j.optJSONObject("result") == null) throw new java.io.IOException("西饭来源暂不可用");
        return j.getJSONObject("result");
    }

    private static List<JSONObject> contents(JSONObject r) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray els = r.optJSONArray("elements");
        if (els != null) for (int i = 0; i < els.length(); i++) {
            JSONObject e = els.optJSONObject(i); JSONArray cs = e == null ? null : e.optJSONArray("contents");
            if (cs != null) for (int k = 0; k < cs.length(); k++) { JSONObject c = cs.optJSONObject(k); if (c != null) out.add(c); }
        }
        return out;
    }

    private static JSONObject hmPage(String path) throws Exception { return nextData(get(HM + path, true)); }

    private static JSONObject nextData(String html) throws Exception {
        Matcher m = NEXT.matcher(html == null ? "" : html);
        if (!m.find()) throw new java.io.IOException("河马目录数据异常");
        return new JSONObject(m.group(1)).getJSONObject("props").getJSONObject("pageProps");
    }

    private static String get(String url, boolean hema) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000); c.setReadTimeout(12000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 Chrome/120 Safari/537.36");
            c.setRequestProperty("Accept", hema ? "text/html" : "application/json");
            if (hema) { c.setRequestProperty("pname", "www.kuaikaw.cn"); c.setRequestProperty("Referer", HM + "/"); }
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            return read(c.getInputStream());
        } finally { c.disconnect(); }
    }

    private static String post(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000); c.setReadTimeout(12000); c.setDoOutput(true); c.setRequestMethod("POST");
            c.setRequestProperty("User-Agent", "Mozilla/5.0"); c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("pname", "www.kuaikaw.cn"); c.setRequestProperty("Origin", HM); c.setRequestProperty("Referer", HM + "/");
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            return read(c.getInputStream());
        } finally { c.disconnect(); }
    }

    /** 跟着 302 走到最终的媒体地址（最多 5 跳）。 */
    private static String resolve(String url) throws Exception {
        String cur = url;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            try {
                c.setInstanceFollowRedirects(false); c.setConnectTimeout(8000); c.setReadTimeout(8000);
                c.setRequestMethod("HEAD"); c.setRequestProperty("User-Agent", "Mozilla/5.0");
                int code = c.getResponseCode();
                if (code / 100 != 3) return cur;
                String loc = c.getHeaderField("Location");
                if (loc == null || loc.isEmpty()) return cur;
                cur = new URL(new URL(cur), loc).toString();
            } finally { c.disconnect(); }
        }
        return cur;
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[16384]; int n, total = 0;
            while ((n = s.read(b)) > 0) { out.write(b, 0, n); total += n; if (total > 8 * 1024 * 1024) throw new java.io.IOException("响应过大"); }
            return out.toString("UTF-8");
        }
    }

    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }
    private static String plain(String s) { return s == null ? "" : s.replaceAll("<[^>]*>", "").trim(); }
    private static String https(String s) { return s != null && s.startsWith("https://") ? s : ""; }
    private static long heat(String t) {
        if (t == null || t.isEmpty()) return 0;
        try {
            double v = Double.parseDouble(t.replaceAll("[^0-9.]", ""));
            if (t.contains("亿")) v *= 1e8; else if (t.contains("万")) v *= 1e4;
            return (long) v;
        } catch (Exception e) { return 0; }
    }
}
