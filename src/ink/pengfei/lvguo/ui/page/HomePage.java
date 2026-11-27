package ink.pengfei.lvguo.ui.page;

import android.content.Intent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import ink.pengfei.lvguo.App;
import ink.pengfei.lvguo.model.Series;
import ink.pengfei.lvguo.net.Browse;
import ink.pengfei.lvguo.net.Catalog;
import ink.pengfei.lvguo.net.Repo;
import ink.pengfei.lvguo.store.Store;
import ink.pengfei.lvguo.ui.IconView;
import ink.pengfei.lvguo.ui.Page;
import ink.pengfei.lvguo.ui.RootActivity;
import ink.pengfei.lvguo.ui.Ui;
import ink.pengfei.lvguo.ui.widget.GlassMenu;
import ink.pengfei.lvguo.ui.widget.HScrollRow;
import ink.pengfei.lvguo.ui.widget.NfCard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 果多首页（照 GuoDuo iOS 奈飞式首页 + 奈飞电视版 2025 交互）：
 * 顶部 GuoDuo logo + 分区药丸（全部 / 真人剧 / 漫剧 / 类别 ▾）+ 搜索 / 手机遥控，
 * 上方「看板」跟着焦点换成当前选中那部（剧名 / 集数热度题材 / 简介，背后大图跟着换），
 * 下面一行一类左右滑：继续观看、我的追剧、今日热播 Top 10（描边大数字）、各榜单。
 * 卡片获得焦点后原地展开成宽卡（{@link NfCard}）；滑到哪行才拉哪行。
 */
public class HomePage extends Page {
    /** {分区 id, 名称}：前两个是顶部药丸，后两个收进「类别」。和 iOS 版同一组。 */
    private static final String[][] ZONES = {
            {"human", "真人剧"}, {"comic_series_rank", "漫剧"}, {"ai_playlet", "AI剧"}, {"series_album", "系列剧"}};
    private static final int PILL_ZONES = 2;
    private static final int ROW_MAX = 20;
    /** 大海报高度（首页内容区顶部）。分区药丸那一行去掉了。 */
    private static final int HERO_DP = 236;
    private ink.pengfei.lvguo.ui.widget.HeroBanner hero;

    /** 一行：分区 | 榜 | 标题 | 是否 Top 10。 */
    private static final class RowSpec {
        final String zone, board, title; final boolean top10;
        RowSpec(String zone, String board, String title, boolean top10) { this.zone = zone; this.board = board; this.title = title; this.top10 = top10; }
        String key() { return zone + "|" + board; }
    }
    private static final RowSpec[] ALL_ROWS = {
            new RowSpec("all", "ranklist_hot_play_sc", "今日热播 Top 10", true),
            new RowSpec("all", "ranklist_hot_sc", "为你推荐", false),
            new RowSpec("all", "ranklist_new_rank_sc", "新剧上线", false),
            new RowSpec("all", "ranklist_must_watch", "口碑必看", false),
            new RowSpec("comic_series_rank", "comic_series_hot_rank", "漫剧推荐", false),
            new RowSpec("xifan", "", "西饭 · 都市热播", false),
            new RowSpec("all", "ranklist_hot_search_sc", "大家都在搜", false),
            new RowSpec("human", "human_hot_play", "真人剧热播", false),
            new RowSpec("all", "ranklist_chase_update", "正在追更", false),
            new RowSpec("series_album", "series_album_new_rank", "系列剧新季", false),
            new RowSpec("hema", "", "河马 · 免费短剧", false),
            new RowSpec("ai_playlet", "ai_playlet_hot_sc", "AI剧推荐", false),
            new RowSpec("all", "ranklist_followed", "收藏最多", false),
            new RowSpec("all", "ranklist_prestige", "臻果精选", false),
            new RowSpec("all", "ranklist_subscribe", "即将上线", false),
    };
    /** 各行数据缓存（分区|榜），切分区再切回来不重拉。 */
    private static final Map<String, List<Series>> CACHE = new HashMap<>();

    private ScrollView scroll;
    private LinearLayout column, bar;
    private TextView eyebrow, title, meta, intro;
    private LinearLayout info;
    private final List<TextView> chips = new ArrayList<>();
    private TextView categoryChip, searchButton, phoneButton;
    private GlassMenu categoryMenu;
    private String zone = "";
    private int generation;
    private String shownId = "";
    private boolean started, billboardFromUser;
    private final Set<String> detailFilled = new HashSet<>();
    /** {RowSpec, section, HScrollRow, boolean[] loaded} */
    private final List<Object[]> rows = new ArrayList<>();
    private LinearLayout continueSection, favSection;
    private HScrollRow continueRow, favRow;

    // ------------------------------------------------------------ 界面

    @Override protected View build() {
        FrameLayout root = new FrameLayout(ctx);
        root.setClipChildren(true);

        // 大海报（照 GuoDuo iOS 新版）：模糊铺底 + 剧名简介 + 右侧完整竖海报；焦点停稳 1.2 秒在海报位置直接播预告
        hero = new ink.pengfei.lvguo.ui.widget.HeroBanner(ctx, dp(HERO_DP));
        root.addView(hero, new FrameLayout.LayoutParams(-1, dp(HERO_DP), Gravity.TOP));

        // 下面各行（焦点换行时由 snapRow 对齐，不让系统自己乱滚）
        scroll = new ScrollView(ctx) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) { return 0; }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFocusable(false);
        scroll.setClipChildren(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(14));
        column = Ui.column(ctx);
        column.setClipChildren(false);
        column.setPadding(0, dp(6), 0, dp(260));   // 底部留白：最后一行也能滚到顶上对齐
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, -1);
        sl.topMargin = dp(HERO_DP);
        root.addView(scroll, sl);
        scroll.getViewTreeObserver().addOnScrollChangedListener(this::loadVisibleRows);

        return root;
    }

    private View buildHeader() {
        // 果多：logo / 搜索 / 设置 / 手机遥控都在全局顶部导航里，这里只留分区药丸
        bar = Ui.row(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(44), dp(4), dp(48), 0);
        bar.setClipChildren(false);

        chips.clear();
        chips.add(chip("全部", ""));
        for (int i = 0; i < PILL_ZONES; i++) chips.add(chip(ZONES[i][1], ZONES[i][0]));
        categoryChip = chip("类别 ▾", null);
        categoryChip.setOnClickListener(v -> categoryMenu.show(0));
        chips.add(categoryChip);
        for (TextView c : chips) {
            LinearLayout.LayoutParams lp = Ui.lp(-2, dp(34)); lp.rightMargin = dp(8);
            bar.addView(c, lp);
        }
        // 药丸行：左右在行内走到头停住；上键交给 RootActivity（到顶部导航）
        chips.get(0).setNextFocusLeftId(chips.get(0).getId());
        categoryChip.setNextFocusRightId(categoryChip.getId());
        paintChips();
        return bar;
    }

    private List<TextView> allChips() {
        List<TextView> all = new ArrayList<>(chips);
        if (searchButton != null) all.add(searchButton);
        if (phoneButton != null) all.add(phoneButton);
        return all;
    }

    private TextView chip(String text, final String zoneId) {
        final TextView c = Ui.text(ctx, text, 14, Ui.TEXT_2, false);
        c.setId(View.generateViewId());
        c.setSingleLine(true);
        c.setGravity(Gravity.CENTER);
        c.setIncludeFontPadding(false);
        c.setFocusable(true);
        c.setCompoundDrawablePadding(dp(6));
        if (android.os.Build.VERSION.SDK_INT >= 26) c.setDefaultFocusHighlightEnabled(false);
        Ui.padding(c, 16, 0, 16, 0);
        c.setTag(zoneId);
        c.setOnFocusChangeListener((v, has) -> paintChips());
        if (zoneId != null) c.setOnClickListener(v -> selectZone(zoneId));
        return c;
    }

    /** 药丸：玻璃淡底；选中 = 亮一档 + 白字加粗；聚焦 = 白底黑字（tvOS 语言）。 */
    private void paintChips() {
        for (TextView c : allChips()) {
            Object tag = c.getTag();
            boolean on = tag instanceof String && tag.equals(zone) || c == categoryChip && isCategoryZone(zone);
            boolean focus = c.hasFocus();
            int fg = focus ? Ui.ON_FOCUS : on ? 0xFFFFFFFF : Ui.TEXT_2;
            c.setBackground(focus ? Ui.pill(ctx, Ui.FOCUS_FILL) : on ? Ui.pill(ctx, 0x47FFFFFF) : Ui.pill(ctx, 0x1FFFFFFF));
            c.setTextColor(fg);
            Ui.tintIcons(c, fg);
            c.setTypeface(android.graphics.Typeface.defaultFromStyle(focus || on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        }
        if (categoryChip != null) {
            String label = "类别 ▾";
            for (int i = PILL_ZONES; i < ZONES.length; i++) if (ZONES[i][0].equals(zone)) label = ZONES[i][1] + " ▾";
            categoryChip.setText(label);
        }
    }

    private static boolean isCategoryZone(String z) {
        for (int i = PILL_ZONES; i < ZONES.length; i++) if (ZONES[i][0].equals(z)) return true;
        return false;
    }

    private int dp(float v) { return Ui.dp(ctx, v); }

    // ------------------------------------------------------------ 分区 / 行

    private void selectZone(String z) {
        if (z == null) z = "";
        if (z.equals(zone)) return;
        zone = z;
        paintChips();
        rebuildRows();
    }

    private void rebuildRows() {
        final int token = ++generation;
        column.removeAllViews();
        rows.clear();
        continueSection = favSection = null; continueRow = favRow = null;
        scroll.scrollTo(0, 0);
        billboardFromUser = false; shownId = "";
        if (zone.isEmpty()) {
            continueRow = new HScrollRow(ctx, NfCard.GAP);
            continueSection = section("继续观看", continueRow);
            favRow = new HScrollRow(ctx, NfCard.GAP);
            favSection = section("我的追剧", favRow);
            renderLibrary();
            for (RowSpec r : ALL_ROWS) addRow(r);
            scroll.post(this::loadVisibleRows);
            return;
        }
        // 选了分区：按服务端目录排这个分区的各榜（热播做 Top 10 放最前，预约放最后）
        final String z = zone, zname = zoneName(z);
        App.io().execute(() -> {
            List<String[]> boards;
            try { boards = Browse.zoneBoards(z); } catch (Exception e) { boards = new ArrayList<>(); }
            final List<RowSpec> specs = new ArrayList<>(), mid = new ArrayList<>(), tail = new ArrayList<>();
            for (String[] b : boards) {
                String n = b[1] == null ? "" : b[1];
                if (n.contains("热播") && specs.isEmpty()) specs.add(new RowSpec(z, b[0], zname + "热播 Top 10", true));
                else if (n.contains("预约")) tail.add(new RowSpec(z, b[0], "即将上线", false));
                else mid.add(new RowSpec(z, b[0], boardTitle(n), false));
            }
            specs.addAll(mid); specs.addAll(tail);
            if (specs.isEmpty()) specs.add(new RowSpec(z, defaultBoard(z), zname + "推荐", false));
            host.runOnUiThread(() -> {
                if (token != generation || root == null) return;
                for (RowSpec r : specs) addRow(r);
                scroll.post(this::loadVisibleRows);
            });
        });
    }

    private static String boardTitle(String n) {
        switch (n) {
            case "推荐榜": return "为你推荐";
            case "新剧榜": return "新剧上线";
            case "新季榜": return "新季上线";
            case "必看榜": return "口碑必看";
            case "热搜榜": return "大家都在搜";
            case "收藏榜": return "收藏最多";
            default: return n;
        }
    }

    private static String defaultBoard(String z) {
        switch (z) {
            case "human": return "human_hot_sc";
            case "comic_series_rank": return "comic_series_hot_rank";
            case "ai_playlet": return "ai_playlet_hot_sc";
            case "series_album": return "series_album_hot_sc";
            default: return Repo.BOARD_RECOMMEND;
        }
    }

    private static String zoneName(String z) {
        for (String[] x : ZONES) if (x[0].equals(z)) return x[1];
        return "";
    }

    private LinearLayout section(String text, HScrollRow row) {
        LinearLayout sec = Ui.column(ctx);
        sec.setClipChildren(false);
        TextView t = Ui.text(ctx, text, 17, Ui.TEXT, true);
        t.setPadding(dp(48), 0, dp(48), dp(8));
        sec.addView(t, Ui.lp(-1, -2));
        row.setPadding(dp(45), 0, dp(48), 0);
        row.setClipToPadding(false);
        sec.addView(row, Ui.lp(-1, dp(NfCard.H + 6)));
        LinearLayout.LayoutParams lp = Ui.lp(-1, -2); lp.bottomMargin = dp(16);
        column.addView(sec, lp);
        sec.setVisibility(View.GONE);
        sec.setTag(row);
        return sec;
    }

    private void addRow(RowSpec spec) {
        HScrollRow row = new HScrollRow(ctx, NfCard.GAP);
        LinearLayout sec = section(spec.title, row);
        sec.setVisibility(View.VISIBLE);   // 先占位，拉到数据再淡入；空的再隐藏
        sec.setAlpha(0f);
        rows.add(new Object[]{spec, sec, row, new boolean[]{false}});
    }

    /** 只拉看得见（以及下面一屏多）的行。 */
    private void loadVisibleRows() {
        if (scroll == null) return;
        int bottom = scroll.getScrollY() + Math.max(scroll.getHeight(), dp(400)) * 2;
        for (Object[] r : rows) {
            boolean[] loaded = (boolean[]) r[3];
            View sec = (View) r[1];
            if (loaded[0] || sec.getTop() > bottom) continue;
            loaded[0] = true;
            loadRow((RowSpec) r[0], sec, (HScrollRow) r[2]);
        }
    }

    private void loadRow(final RowSpec spec, final View sec, final HScrollRow row) {
        List<Series> cached;
        synchronized (CACHE) { cached = CACHE.get(spec.key()); }
        if (cached != null) { fillRow(spec, sec, row, cached); return; }
        final int token = generation;
        App.io().execute(() -> {
            List<Series> items = new ArrayList<>();
            try {
                if (ink.pengfei.lvguo.net.OtherSources.XIFAN.equals(spec.zone) || ink.pengfei.lvguo.net.OtherSources.HEMA.equals(spec.zone))
                    items.addAll(ink.pengfei.lvguo.net.OtherSources.browse(spec.zone, spec.board, 1).items);
                else items.addAll(Browse.rankIn(spec.zone, spec.board, 0, UUID.randomUUID().toString()).items);
            }
            catch (Exception ignored) { }
            if (!items.isEmpty()) synchronized (CACHE) { CACHE.put(spec.key(), items); }
            final List<Series> got = items;
            host.runOnUiThread(() -> { if (token == generation && root != null) fillRow(spec, sec, row, got); });
        });
    }

    private void fillRow(RowSpec spec, View sec, HScrollRow row, List<Series> items) {
        if (items.isEmpty()) { sec.setVisibility(View.GONE); scroll.post(this::loadVisibleRows); return; }
        boolean hadFocus = row.hasFocusInside();
        row.clear();
        int n = 0;
        for (final Series s : items) {
            if (n >= (spec.top10 ? 10 : ROW_MAX)) break;
            n++;
            NfCard card = new NfCard(ctx, s, spec.top10 ? n : 0);
            String kicker = spec.top10 ? spec.title.replace(" Top 10", "") + " · 第 " + n + " 名" : spec.title;
            bindCard(card, s, kicker);
            card.setOnClickListener(v -> { hero.stopPreview(); host.openDetail(s); });
            row.add(card);
        }
        // 行尾「更多」：进这个分类的全部（网格，滚到底自动续页）
        if (!items.isEmpty()) row.add(moreCard(spec.title.replace(" Top 10", ""), rowSource(spec, items)));
        if (row.size() > 0) row.item(0).setNextFocusLeftId(row.item(0).getId());
        sec.animate().cancel();
        sec.animate().alpha(1f).setDuration(220).start();
        if (!billboardFromUser && shownId.isEmpty()) showInfo(items.get(0), spec.title);
        if (hadFocus && !row.focusRemembered()) row.focusFirst();
        maybeAutoFocus();
    }

    private View moreCard(String title, ListPage page) {
        ink.pengfei.lvguo.ui.widget.MoreCard m = new ink.pengfei.lvguo.ui.widget.MoreCard(ctx, title);
        m.setOnClickListener(v -> { hero.stopPreview(); host.openList(page); });
        return m;
    }

    /** 某一行的完整列表：先放已拿到的这批，再按榜单接口一页页往下翻。 */
    private ListPage rowSource(RowSpec spec, List<Series> first) {
        final String title = spec.title.replace(" Top 10", "");
        final String[] session = {UUID.randomUUID().toString()};
        if (ink.pengfei.lvguo.net.OtherSources.XIFAN.equals(spec.zone) || ink.pengfei.lvguo.net.OtherSources.HEMA.equals(spec.zone))
            return new ListPage(title, cursor -> {
                ink.pengfei.lvguo.net.OtherSources.Page pg = ink.pengfei.lvguo.net.OtherSources.browse(spec.zone, spec.board, Math.max(1, cursor));
                return new ListPage.Chunk(pg.items, pg.next, pg.more);
            }, null);
        return new ListPage(title, cursor -> {
            Browse.Result r = Browse.rankIn(spec.zone, spec.board, cursor, session[0]);
            if (r.session != null && !r.session.isEmpty()) session[0] = r.session;
            return new ListPage.Chunk(r.items, r.next, r.more);
        }, null);
    }

    private void bindCard(NfCard card, Series s, String kicker) {
        card.setOnFocused(() -> {
            billboardFromUser = true;
            showInfo(s, kicker);
            snapRow(card);
            ink.pengfei.lvguo.media.PlaybackWarmup.schedule(s.seriesId);
        });
    }

    /** 焦点所在那行对齐到内容区顶部（奈飞：焦点行永远在同一高度）。 */
    private void snapRow(View card) {
        View sec = card;
        while (sec.getParent() instanceof View && sec.getParent() != column) sec = (View) sec.getParent();
        if (sec.getParent() != column) return;
        scroll.smoothScrollTo(0, Math.max(0, sec.getTop() - dp(6)));
    }

    // ------------------------------------------------------------ 继续观看 / 我的追剧

    private void renderLibrary() {
        if (continueRow == null) return;
        Map<String, Series> catalog = new HashMap<>();
        List<Series> cached = Catalog.cached();
        if (cached != null) for (Series s : cached) catalog.put(s.seriesId, s);

        boolean hadFocus = continueRow.hasFocusInside();
        continueRow.clear();
        Set<String> done = Store.completedIds();
        int n = 0;
        for (final Store.Item item : Store.history()) {
            if (done.contains(item.seriesId)) continue;
            if (item.episodeCount > 0 && item.lastEpisode >= item.episodeCount && (item.durationMs <= 0 || item.positionMs >= item.durationMs - 15000)) continue;
            if (n++ >= ROW_MAX) break;
            final Series s = fromItem(item, catalog);
            NfCard card = new NfCard(ctx, s, 0);
            String prog = MePage.progress(item);
            if (prog != null) card.setBadge(prog);
            int total = s.episodeCount > 0 ? s.episodeCount : item.episodeCount;
            if (total > 0 && item.lastEpisode > 0) card.setProgress(Math.min(1f, item.lastEpisode / (float) total));
            bindCard(card, s, item.lastEpisode > 0 ? "继续观看 · 第 " + item.lastEpisode + " 集" : "继续观看");
            card.setOnClickListener(v -> { hero.stopPreview(); resume(item, s); });
            continueRow.add(card);
        }
        if (continueRow.size() > 0) continueRow.item(0).setNextFocusLeftId(continueRow.item(0).getId());
        continueSection.setVisibility(continueRow.size() > 0 ? View.VISIBLE : View.GONE);
        if (hadFocus && continueRow.size() > 0 && !continueRow.focusRemembered()) continueRow.focusFirst();

        boolean favFocus = favRow.hasFocusInside();
        favRow.clear();
        n = 0;
        for (Store.Item it : Store.favorites()) {
            if (n++ >= ROW_MAX) break;
            final Series s = fromItem(it, catalog);
            s.firstVid = it.vid;
            NfCard card = new NfCard(ctx, s, 0);
            bindCard(card, s, "我的追剧");
            card.setOnClickListener(v -> { hero.stopPreview(); host.openDetail(s); });
            favRow.add(card);
        }
        if (favRow.size() > 0) {
            final List<Series> all = new ArrayList<>();
            for (Store.Item it : Store.favorites()) all.add(fromItem(it, catalog));
            favRow.add(moreCard("我的追剧", ListPage.of("我的追剧", all, null)));
        }
        if (favRow.size() > 0) favRow.item(0).setNextFocusLeftId(favRow.item(0).getId());
        favSection.setVisibility(favRow.size() > 0 ? View.VISIBLE : View.GONE);
        if (favFocus && favRow.size() > 0 && !favRow.focusRemembered()) favRow.focusFirst();

        if (!billboardFromUser && continueRow.size() > 0) {
            Store.Item top = Store.history().get(0);
            shownId = "";
            showInfo(fromItem(top, catalog), top.lastEpisode > 0 ? "继续观看 · 第 " + top.lastEpisode + " 集" : "继续观看");
        }
    }

    private static Series fromItem(Store.Item it, Map<String, Series> catalog) {
        Series s = new Series();
        s.seriesId = it.seriesId; s.title = it.title; s.cover = Store.bestCover(it.seriesId, it.cover); s.episodeCount = it.episodeCount;
        Series known = catalog.get(it.seriesId);
        if (known != null) {
            s.intro = known.intro; s.playCount = known.playCount; s.category = known.category;
            if (s.episodeCount <= 0) s.episodeCount = known.episodeCount;
        }
        return s;
    }

    private void resume(Store.Item item, Series s) {
        Intent intent = new Intent(host, ink.pengfei.lvguo.PlayerActivity.class);
        intent.putExtra("series_id", item.seriesId); intent.putExtra("title", item.title);
        intent.putExtra("cover", s.cover); intent.putExtra("index", item.episodeIndex);
        if (item.vid != null && !item.vid.isEmpty()) {
            intent.putExtra(ink.pengfei.lvguo.PlayerActivity.EXTRA_VID, item.vid);
            intent.putExtra(ink.pengfei.lvguo.PlayerActivity.EXTRA_EPISODE_NO, item.lastEpisode);
        }
        intent.putExtra("position_ms", item.positionMs);
        host.startActivity(intent);
    }

    // ------------------------------------------------------------ 看板

    private void showInfo(Series s, String kicker) {
        if (s == null) return;
        String id = s.seriesId == null ? "" : s.seriesId;
        hero.show(s, kicker);
        if (!id.equals(shownId)) host.setCoverBackground(s.cover);
        shownId = id;
        // 列表接口没带简介 / 集数 / 热度的：单独拉一次详情补上（每部只补一次）
        String text = s.intro == null ? "" : s.intro.trim();
        if ((text.isEmpty() || s.episodeCount <= 0 || s.playCount <= 0) && !id.isEmpty() && detailFilled.add(id)) {
            final String k = kicker;
            App.io().execute(() -> {
                try {
                    Repo.Detail d = Repo.detail(id);
                    if (s.intro == null || s.intro.trim().isEmpty()) s.intro = d.intro;
                    if (s.episodeCount <= 0) s.episodeCount = d.episodes.size();
                    if (s.playCount <= 0) s.playCount = d.playCount;
                    if ((s.firstVid == null || s.firstVid.isEmpty()) && !d.episodes.isEmpty()) s.firstVid = d.episodes.get(0).vid;
                    host.runOnUiThread(() -> { if (id.equals(shownId)) hero.show(s, k); });
                } catch (Exception ignored) { }
            });
        }
    }

    private static List<String> tags(String raw, int max) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        if (!raw.trim().startsWith("[")) {
            for (String n : raw.split("[,，|/]")) { n = n.trim(); if (!n.isEmpty() && out.size() < max) out.add(n); }
            return out;
        }
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length() && out.size() < max; i++) {
                org.json.JSONObject t = arr.optJSONObject(i);
                String name = t == null ? "" : t.optString("name");
                if (!name.isEmpty()) out.add(name);
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ------------------------------------------------------------ 生命周期 / 焦点

    private final Store.Listener libraryListener = () -> { if (root != null && root.isShown()) renderLibrary(); };
    private boolean listening, autoFocus = true;

    @Override public void onHide() { if (hero != null) hero.setActive(false); }

    @Override public void onShow() {
        if (hero != null) { hero.setActive(true); hero.replay(); }
        if (!listening) { listening = true; Store.listen(libraryListener); }
        if (!started) { started = true; rebuildRows(); return; }
        renderLibrary();
    }

    private void maybeAutoFocus() {
        if (!autoFocus) return;
        if (host.currentTab() != RootActivity.TAB_HOME || host.isOverlayOpen()) return;
        View f = host.getWindow().getDecorView().findFocus();
        autoFocus = false;
        if (f != null && f.isShown()) return;
        focusFirst();
    }

    @Override public boolean onBack() {
        if (categoryMenu != null && categoryMenu.isShowing()) { categoryMenu.hide(); categoryChip.requestFocus(); return true; }
        // 光标在下面选剧：先回到顶部导航「首页」；已经在顶部导航再按才是退出确认
        View f = host.getWindow().getDecorView().findFocus();
        if (f != null && column != null && isInside(column, f)) {
            if (scroll != null) scroll.smoothScrollTo(0, 0);
            host.focusTopNav();
            return true;
        }
        return false;
    }

    @Override public boolean returnToTop() { return onBack(); }

    /**
     * 由 RootActivity 转发按键：「类别」下拉开着时给它；内容区上下键自己按行走（奈飞：上下一定是相邻那一行，
     * 落在该行上次停的那张，没停过就落在横向位置最近的那张；第一行再往上 = 顶栏当前分区药丸）。
     */
    public boolean handleKey(KeyEvent e) {
        if (categoryMenu != null && categoryMenu.isShowing()) return categoryMenu.handleKey(e);
        int k = e.getKeyCode();
        if (k != KeyEvent.KEYCODE_DPAD_UP && k != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        View f = host.getWindow().getDecorView().findFocus();
        if (f == null || root == null) return false;
        boolean inHeader = isInside(bar, f), inRows = isInside(column, f);
        if (!inHeader && !inRows) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        List<View> secs = visibleSections();
        if (inHeader) {
            if (k == KeyEvent.KEYCODE_DPAD_DOWN && !secs.isEmpty()) focusRow(secs.get(0), f);
            else if (k == KeyEvent.KEYCODE_DPAD_UP) host.focusTopNav();
            return true;
        }
        View sec = f;
        while (sec.getParent() instanceof View && sec.getParent() != column) sec = (View) sec.getParent();
        int at = secs.indexOf(sec);
        if (at < 0) return false;
        int to = at + (k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
        if (to < 0) { scroll.smoothScrollTo(0, 0); host.focusTopNav(); return true; }
        if (to >= secs.size()) return true;   // 最后一行再往下：停住
        focusRow(secs.get(to), f);
        return true;
    }

    private List<View> visibleSections() {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < column.getChildCount(); i++) {
            View sec = column.getChildAt(i);
            if (sec.getVisibility() == View.VISIBLE && sec.getAlpha() > 0.01f && sec.getTag() instanceof HScrollRow && ((HScrollRow) sec.getTag()).size() > 0) out.add(sec);
        }
        return out;
    }

    private void focusRow(View sec, View from) {
        HScrollRow row = (HScrollRow) sec.getTag();
        View target = row.rememberedFocus();
        if (target == null) {
            // 没停过：选屏幕上横向位置最近的一张
            int[] p = new int[2]; from.getLocationOnScreen(p);
            int cx = p[0] + from.getWidth() / 2, best = Integer.MAX_VALUE;
            for (int i = 0; i < row.size(); i++) {
                View v = row.item(i); int[] q = new int[2]; v.getLocationOnScreen(q);
                int d = Math.abs(q[0] + v.getWidth() / 2 - cx);
                if (d < best) { best = d; target = v; }
            }
        }
        if (target != null) target.requestFocus();
    }

    private void focusHeaderChip() {
        TextView target = chips.get(0);
        for (TextView c : chips) if (c.getTag() instanceof String && c.getTag().equals(zone)) target = c;
        if (isCategoryZone(zone)) target = categoryChip;
        target.requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    private static boolean isInside(View parent, View v) {
        for (View cur = v; cur != null; cur = cur.getParent() instanceof View ? (View) cur.getParent() : null) if (cur == parent) return true;
        return false;
    }

    @Override public void focusFirst() {
        for (int i = 0; i < column.getChildCount(); i++) {
            View sec = column.getChildAt(i);
            if (sec.getVisibility() != View.VISIBLE || !(sec.getTag() instanceof HScrollRow)) continue;
            HScrollRow r = (HScrollRow) sec.getTag();
            if (r.size() == 0) continue;
            r.smoothScrollTo(0, 0);
            r.focusFirst();
            scroll.smoothScrollTo(0, 0);
            return;
        }
        host.focusTopNav();
    }

    @Override public void bindNav(int id) { }

    /** 从搜索页返回：焦点回到首页内容。 */
    public void focusSearchButton() { focusFirst(); }

    /** 给「我的」页「查看全部」拼图用：从第 from 部起取 4 张封面。 */
    static List<String> coversAfter(List<Series> items, int from) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < items.size() && out.size() < 4; i++) out.add(items.get(i).cover);
        for (int i = 0; i < items.size() && out.size() < 4; i++) out.add(items.get(i).cover);
        return out;
    }
}
