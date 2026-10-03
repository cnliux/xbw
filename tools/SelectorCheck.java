import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * 用真实抓取到的 yikm.net HTML 验证 Jsoup 选择器（与 App 内 YikmParser 逻辑一致）。
 */
public class SelectorCheck {

    static final String CARD_SELECTOR = "div.card-blog";
    static final String TITLE_SELECTOR = "h4.card-caption a";
    static final String COVER_SELECTOR = "div.card-image img";
    static final String LABEL_SELECTOR = "span.label";
    static final String PLAY_HREF_PREFIX = "/play?id=";
    static final String NEXT_PAGE_SELECTOR = "ul.pagination a[ href*='page=']";
    static final String CATEGORY_SELECTOR = "div.container > div.row > div.col-md-3 div.card-blog, "
            + "#hotgames .card-blog, .hot-li";

    public static void main(String[] args) throws Exception {
        String dir = "G:/git/xbw/_probe/";
        check("home.html", true);
        check("list.html", false);
        check("arcade.html", false);
        check("search.html", false);
        check("so.html", false);
        playPageButtons(dir + "play.html");
    }

    static void check(String file, boolean isHome) throws Exception {
        String html = Files.readString(new File(dir0() + file).toPath(), StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(html);
        Elements cards = doc.select(CARD_SELECTOR);
        System.out.println("== " + file + " ==  cards=" + cards.size());
        int ok = 0, noCover = 0, noTitle = 0;
        List<String> samples = new ArrayList<>();
        for (Element c : cards) {
            Element a = c.selectFirst(TITLE_SELECTOR);
            Element img = c.selectFirst(COVER_SELECTOR);
            if (a == null || a.attr("href").isEmpty()) { noTitle++; continue; }
            String href = a.attr("href");
            if (!href.contains("/play?id=")) continue;   // 过滤推荐链接
            String name = a.text().trim();
            String cover = img == null ? null : (img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src"));
            if (cover == null || cover.isEmpty()) noCover++;
            List<String> labels = new ArrayList<>();
            for (Element s : c.select(LABEL_SELECTOR)) labels.add(s.text().trim());
            if (name.isEmpty()) { noTitle++; continue; }
            ok++;
            if (samples.size() < 3) {
                samples.add("  [" + name + "] href=" + href + " cover=" + abbrev(cover) + " labels=" + labels);
            }
        }
        System.out.println("  parsed=" + ok + " noTitle=" + noTitle + " noCover=" + noCover);
        samples.forEach(System.out::println);

        // 分页
        Elements pages = doc.select(NEXT_PAGE_SELECTOR);
        Set<String> pset = new LinkedHashSet<>();
        for (Element p : pages) pset.add(p.text().trim() + " -> " + p.attr("href"));
        System.out.println("  pagination entries=" + pset.size() + (pset.isEmpty() ? "" : " sample=" + new ArrayList<>(pset).subList(0, Math.min(3, pset.size()))));

        if (isHome) {
            // 首页：热门区块 + "更多"链接 + 底部所有游戏卡
            Elements h2s = doc.select("div.container h2");
            System.out.println("  home h2 count=" + h2s.size());
            for (Element h : h2s) System.out.println("    h2: " + h.text());
            Elements h3s = doc.select("div.container h3");
            for (Element h : h3s) System.out.println("    h3: " + h.text());
            Elements more = doc.select("a[href^='/nes?'], a[href='/h5']");
            System.out.println("  more-links=" + more.size());
            for (Element m : more) System.out.println("    " + m.text().trim().replace("\n", " ") + " -> " + m.attr("href"));
        }
        // 分类过滤验证：街机页不应包含纯 FC-only 游戏封面域名，仅看平台标签分布
        Map<String, Integer> domainCount = new LinkedHashMap<>();
        for (Element c : cards) {
            Element img = c.selectFirst(COVER_SELECTOR);
            if (img == null) continue;
            String src = img.attr("src");
            int i = src.indexOf("/fcpic") >= 0 ? src.indexOf("/fcpic")
                    : src.indexOf("/arcadepic") >= 0 ? src.indexOf("/arcadepic") : -1;
            String tag = src.contains("/fcpic") ? "fcpic" : src.contains("/arcadepic") ? "arcadepic"
                    : src.contains("/gbapic") ? "gbapic" : src.contains("/dospic") ? "dospic"
                    : src.contains("/flashpic") ? "flashpic" : src.contains("/jarimgs") ? "jarimgs"
                    : src.contains("/thirdh5") ? "thirdh5" : "other";
            domainCount.merge(tag, 1, Integer::sum);
        }
        System.out.println("  cover buckets: " + domainCount);
    }

    static String dir0() { return "G:/git/xbw/_probe/"; }
    static String abbrev(String s) {
        if (s == null) return "null";
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    /** 校验游戏页覆盖层要触发的站点原生按钮是否存在 */
    static void playPageButtons(String path) throws Exception {
        Document doc = Jsoup.parse(new File(path), "UTF-8");
        String[] ids = {"GamePauseOrPlay", "resetButton", "max_screen", "SaveListButton",
                "SwitchShaderButton", "gameControllerButton", "videoSettingButton",
                "quickGameSave", "showCheatButton", "close_gameroomModal"};
        System.out.println("== play.html native buttons ==");
        for (String id : ids) {
            Element e = doc.getElementById(id);
            System.out.println("  #" + id + " = " + (e != null ? "FOUND" : "missing"));
        }
        System.out.println("  #canvas = " + (doc.getElementById("canvas") != null));
        System.out.println("  #loading-screen = " + (doc.getElementById("loading-screen") != null));
        System.out.println("  iframe count = " + doc.select("iframe").size());
        // 广告容器
        Elements ads = doc.select("ins.adsbygoogle, div.adsbygoogle, .adsbygoogle, [id^=div-gpt-ad], .ad-box, [class*=ad-]");
        System.out.println("  ad elements = " + ads.size());
        // 内联脚本变量
        String html = Files.readString(new File(path).toPath(), StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("var\\s+hascheat[\\s\\S]{0,300}?gameid=\"(\\d+)\",gname=\"([^\"]+)\"").matcher(html);
        System.out.println("  inline vars: " + (m.find() ? "gameid=" + m.group(1) + " gname=" + m.group(2) : "NOT FOUND"));
    }
}
