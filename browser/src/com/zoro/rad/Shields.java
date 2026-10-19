package com.zoro.rad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rad Shields: an ad blocker that reads the same public filter lists uBlock Origin uses (EasyList,
 * EasyPrivacy, uBlock filters, Peter Lowe's list) and answers three questions fast:
 *   shouldBlock(url, page)   network requests (ads, trackers)
 *   blockPopup(url, page)    new windows and pop unders
 *   css(pageHost)            element hiding, so blocked ads leave no empty boxes
 * Supported syntax: ||host^ anchors, | start and end anchors, * and ^ wildcards, @@ exceptions,
 * $third-party, $domain=, $popup, $document and $generichide exceptions, ## and #@# element hiding.
 * Scriptlets, procedural cosmetics, redirects and header rules are skipped.
 */
final class Shields {

    /** One network rule that is not a plain blocked host. */
    private static final class Rule {
        String pattern;          // the filter text without options, e.g. "||ads.example.com/banner^"
        Pattern regex;           // compiled lazily
        int party;               // 0 any, 1 third party only, -1 first party only
        String[] domains;        // $domain= includes, null if none
        String[] notDomains;     // $domain= excludes, null if none

        boolean matches(String url, String host, String pageHost, boolean thirdParty) {
            if (party == 1 && !thirdParty) return false;
            if (party == -1 && thirdParty) return false;
            if (domains != null && (pageHost == null || !anyDomain(pageHost, domains))) return false;
            if (notDomains != null && pageHost != null && anyDomain(pageHost, notDomains)) return false;
            if (regex == null) regex = compile(pattern);
            return regex.matcher(url).find();
        }
    }

    // Plain "||host^" rules, the bulk of every list: a hash lookup per host suffix.
    private final Set<String> blockedHosts = new HashSet<>();
    private final Set<String> blockedHostsThirdParty = new HashSet<>();
    private final Set<String> allowedHosts = new HashSet<>();
    // Everything else, indexed by one token of the pattern so each request only tests a few rules.
    private final Map<String, List<Rule>> block = new HashMap<>();
    private final Map<String, List<Rule>> allow = new HashMap<>();
    private final List<Rule> blockNoToken = new ArrayList<>();
    private final Map<String, List<Rule>> popup = new HashMap<>();
    private final Set<String> popupHosts = new HashSet<>();
    // Pages where blocking or generic hiding is switched off by the lists' own exceptions.
    private final List<Rule> documentAllow = new ArrayList<>();
    private final List<Rule> genericHideAllow = new ArrayList<>();
    // Element hiding.
    private final List<String> genericSelectors = new ArrayList<>();
    private final Map<String, List<String>> domainSelectors = new HashMap<>();
    private final Set<String> selectorExceptions = new HashSet<>();
    private String genericCss = "";

    int ruleCount = 0;

    // ------------------------------------------------------------------ loading

    void addList(String text) {
        int start = 0, n = text.length();
        while (start < n) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = n;
            String line = text.substring(start, end).trim();
            start = end + 1;
            if (line.isEmpty() || line.charAt(0) == '!' || line.charAt(0) == '[') continue;
            try { addLine(line); } catch (Exception ignored) { }
        }
    }

    /** Call once after every list is added. */
    void finish() {
        StringBuilder css = new StringBuilder();
        List<String> chunk = new ArrayList<>();
        for (String sel : genericSelectors) {
            if (selectorExceptions.contains(sel)) continue;
            chunk.add(sel);
            if (chunk.size() == 400) { appendCss(css, chunk); chunk.clear(); }
        }
        appendCss(css, chunk);
        genericCss = css.toString();
    }

    private static void appendCss(StringBuilder css, List<String> sels) {
        if (sels.isEmpty()) return;
        // :is() is forgiving: one selector this engine cannot parse does not void the whole group.
        css.append(":is(").append(String.join(",", sels)).append("){display:none!important}\n");
    }

    private void addLine(String line) {
        // Element hiding: "##", "#@#". Skip procedural (#?#), scriptlet and style injection (#$#, ##+js, #%#).
        int hash = line.indexOf("##");
        int hashAt = line.indexOf("#@#");
        if (line.contains("#?#") || line.contains("#$#") || line.contains("#%#") || line.contains("##+js")
                || line.contains("#@?#") || line.contains("#@$#")) return;
        if (hashAt >= 0) {
            selectorExceptions.add(line.substring(hashAt + 3).trim());
            return;
        }
        if (hash >= 0) {
            String domains = line.substring(0, hash).trim();
            String sel = line.substring(hash + 2).trim();
            if (sel.isEmpty() || sel.startsWith("^") || sel.contains(":has-text(") || sel.contains(":-abp-")
                    || sel.contains(":upward(") || sel.contains(":xpath(") || sel.contains(":remove(")
                    || sel.contains(":style(") || sel.contains(":matches-")) return;
            if (domains.isEmpty()) {
                genericSelectors.add(sel);
            } else {
                for (String d : domains.split(",")) {
                    d = d.trim().toLowerCase(Locale.US);
                    if (d.isEmpty() || d.startsWith("~")) continue;
                    List<String> l = domainSelectors.get(d);
                    if (l == null) domainSelectors.put(d, l = new ArrayList<>());
                    l.add(sel);
                }
            }
            ruleCount++;
            return;
        }

        boolean exception = line.startsWith("@@");
        if (exception) line = line.substring(2);
        // A whole regex rule (/.../): rare in these lists and slow; skip.
        if (line.startsWith("/") && line.endsWith("/") && line.length() > 2) return;

        String pattern = line, opts = "";
        int dollar = line.lastIndexOf('$');
        if (dollar > 0 && !line.substring(dollar).contains("/")) {
            pattern = line.substring(0, dollar);
            opts = line.substring(dollar + 1).toLowerCase(Locale.US);
        }
        if (pattern.isEmpty() || pattern.equals("*")) return;

        Rule r = new Rule();
        r.pattern = pattern;
        boolean isPopup = false, isDocument = false, isGenericHide = false, onlyPopup = true;
        boolean hasOpts = !opts.isEmpty();
        for (String o : opts.split(",")) {
            o = o.trim();
            if (o.isEmpty()) continue;
            switch (o) {
                case "third-party": case "3p": r.party = 1; onlyPopup = false; break;
                case "~third-party": case "1p": case "first-party": r.party = -1; onlyPopup = false; break;
                case "popup": isPopup = true; break;
                case "document": case "doc": isDocument = true; onlyPopup = false; break;
                case "generichide": case "ghide": case "elemhide": case "ehide": isGenericHide = true; onlyPopup = false; break;
                case "important": case "match-case": case "all": onlyPopup = false; break;
                case "script": case "image": case "stylesheet": case "css": case "xmlhttprequest": case "xhr":
                case "subdocument": case "frame": case "media": case "font": case "object": case "ping":
                case "websocket": case "other": case "~script": case "~image": case "~stylesheet":
                case "~xmlhttprequest": case "~subdocument": case "~media": case "~font": case "~object":
                    onlyPopup = false; break;   // type options: applied loosely to every request type
                default:
                    if (o.startsWith("domain=") || o.startsWith("from=")) {
                        List<String> inc = new ArrayList<>(), exc = new ArrayList<>();
                        for (String d : o.substring(o.indexOf('=') + 1).split("\\|")) {
                            if (d.startsWith("~")) exc.add(d.substring(1)); else if (!d.isEmpty()) inc.add(d);
                        }
                        if (!inc.isEmpty()) r.domains = inc.toArray(new String[0]);
                        if (!exc.isEmpty()) r.notDomains = exc.toArray(new String[0]);
                        onlyPopup = false;
                    } else {
                        // csp=, redirect=, removeparam=, header=, badfilter, denyallow= and the like change
                        // what a rule means; applying them as a plain block would break sites. Skip the rule.
                        return;
                    }
            }
        }
        if (!hasOpts) onlyPopup = false;

        if (isPopup) {
            if (exception) return;
            String host = plainHost(pattern);
            if (host != null && r.domains == null) popupHosts.add(host);
            else index(popup, r);
            ruleCount++;
            if (onlyPopup) return;
        }
        if (exception && isDocument) { documentAllow.add(r); return; }
        if (exception && isGenericHide) { genericHideAllow.add(r); return; }
        if (isDocument) return;   // blocking whole pages is out of scope

        String host = plainHost(pattern);
        if (host != null && r.domains == null && r.notDomains == null && r.party != -1) {
            if (exception) allowedHosts.add(host);
            else if (r.party == 1) blockedHostsThirdParty.add(host);
            else blockedHosts.add(host);
        } else {
            index(exception ? allow : block, r);
        }
        ruleCount++;
    }

    /** "||ads.example.com^" -> "ads.example.com"; null when the rule has a path or wildcards. */
    private static String plainHost(String p) {
        if (!p.startsWith("||")) return null;
        String h = p.substring(2);
        if (h.endsWith("^")) h = h.substring(0, h.length() - 1);
        else if (h.endsWith("^|")) h = h.substring(0, h.length() - 2);
        else return null;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_')) return null;
        }
        return h.isEmpty() ? null : h.toLowerCase(Locale.US);
    }

    private void index(Map<String, List<Rule>> map, Rule r) {
        String tok = bestToken(r.pattern);
        if (tok == null) {
            if (map == block) blockNoToken.add(r);
            return;
        }
        List<Rule> l = map.get(tok);
        if (l == null) map.put(tok, l = new ArrayList<>());
        l.add(r);
    }

    /** The longest run of letters and digits in a pattern that is not cut by a wildcard. */
    private static String bestToken(String p) {
        String best = null;
        int i = 0, n = p.length();
        while (i < n) {
            while (i < n && !isTok(p.charAt(i))) i++;
            int s = i;
            while (i < n && isTok(p.charAt(i))) i++;
            if (i - s < 3) continue;
            boolean cutBefore = s > 0 && p.charAt(s - 1) == '*';
            boolean cutAfter = i < n && p.charAt(i) == '*';
            if (cutBefore || cutAfter) continue;
            String t = p.substring(s, i).toLowerCase(Locale.US);
            if (best == null || t.length() > best.length()) best = t;
        }
        return best;
    }

    private static boolean isTok(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '%';
    }

    /** Filter syntax to a regex: ||host anchor, | anchors, * any, ^ separator. */
    static Pattern compile(String p) {
        StringBuilder re = new StringBuilder();
        int i = 0;
        if (p.startsWith("||")) {
            re.append("^[a-z][a-z0-9+.-]*://([^/?#]*\\.)?");
            i = 2;
        } else if (p.startsWith("|")) {
            re.append("^");
            i = 1;
        }
        boolean endAnchor = p.endsWith("|") && p.length() > i;
        int end = endAnchor ? p.length() - 1 : p.length();
        for (; i < end; i++) {
            char c = p.charAt(i);
            if (c == '*') re.append(".*");
            else if (c == '^') re.append("(?:[^\\w.%-]|$)");
            else if ("\\.+?()[]{}$|".indexOf(c) >= 0) re.append('\\').append(c);
            else re.append(c);
        }
        if (endAnchor) re.append("$");
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }

    // ------------------------------------------------------------------ questions

    /** Should this sub request (script, image, frame...) be blocked on a page from pageHost? */
    boolean shouldBlock(String url, String pageUrl) {
        String host = host(url);
        if (host == null) return false;
        String pageHost = host(pageUrl);
        if (pageUrl != null && pageAllowed(pageUrl)) return false;
        boolean third = pageHost == null || !site(host).equals(site(pageHost));
        String lower = url.toLowerCase(Locale.US);

        if (hostIn(host, allowedHosts)) return false;
        boolean blocked = hostIn(host, blockedHosts) || (third && hostIn(host, blockedHostsThirdParty));
        List<String> toks = null;
        if (!blocked) {
            toks = tokens(lower);
            blocked = anyMatch(block, toks, lower, host, pageHost, third);
            if (!blocked) {
                for (Rule r : blockNoToken) if (r.matches(lower, host, pageHost, third)) { blocked = true; break; }
            }
        }
        if (!blocked) return false;
        if (toks == null) toks = tokens(lower);
        return !anyMatch(allow, toks, lower, host, pageHost, third);
    }

    /** Should a new window to url, opened from pageUrl, be stopped? */
    boolean blockPopup(String url, String pageUrl) {
        String host = host(url);
        if (host == null) return false;
        if (hostIn(host, popupHosts) || hostIn(host, blockedHosts) || hostIn(host, blockedHostsThirdParty)) return true;
        String lower = url.toLowerCase(Locale.US);
        String pageHost = host(pageUrl);
        boolean third = pageHost == null || !site(host).equals(site(pageHost));
        return anyMatch(popup, tokens(lower), lower, host, pageHost, third);
    }

    boolean pageAllowed(String pageUrl) {
        String lower = pageUrl.toLowerCase(Locale.US);
        String h = host(pageUrl);
        for (Rule r : documentAllow) if (r.matches(lower, h, h, false)) return true;
        return false;
    }

    /** All element hiding CSS for a page. */
    String css(String pageUrl) {
        String h = host(pageUrl);
        if (h == null || pageAllowed(pageUrl)) return "";
        boolean generic = true;
        String lower = pageUrl.toLowerCase(Locale.US);
        for (Rule r : genericHideAllow) if (r.matches(lower, h, h, false)) { generic = false; break; }
        StringBuilder css = new StringBuilder(generic ? genericCss : "");
        List<String> specific = new ArrayList<>();
        String d = h;
        while (true) {
            List<String> l = domainSelectors.get(d);
            if (l != null) for (String s : l) if (!selectorExceptions.contains(s)) specific.add(s);
            int dot = d.indexOf('.');
            if (dot < 0) break;
            d = d.substring(dot + 1);
        }
        appendCss(css, specific);
        return css.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static boolean anyMatch(Map<String, List<Rule>> map, List<String> toks, String url,
                                    String host, String pageHost, boolean third) {
        for (String t : toks) {
            List<Rule> l = map.get(t);
            if (l == null) continue;
            for (Rule r : l) if (r.matches(url, host, pageHost, third)) return true;
        }
        return false;
    }

    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && !isTok(s.charAt(i))) i++;
            int st = i;
            while (i < n && isTok(s.charAt(i))) i++;
            if (i - st >= 3) out.add(s.substring(st, i));
        }
        return out;
    }

    private static boolean hostIn(String host, Set<String> set) {
        if (set.isEmpty()) return false;
        String h = host;
        while (true) {
            if (set.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) return false;
            h = h.substring(dot + 1);
        }
    }

    private static boolean anyDomain(String host, String[] list) {
        for (String d : list) if (host.equals(d) || host.endsWith("." + d)) return true;
        return false;
    }

    static String host(String url) {
        if (url == null) return null;
        int s = url.indexOf("://");
        if (s < 0) return null;
        s += 3;
        int e = s;
        while (e < url.length() && "/?#:".indexOf(url.charAt(e)) < 0) e++;
        int at = url.lastIndexOf('@', e);
        if (at >= s) s = at + 1;
        return e > s ? url.substring(s, e).toLowerCase(Locale.US) : null;
    }

    /** A rough registrable domain: the last two labels, or three for co.uk style endings. */
    static String site(String host) {
        if (host == null) return "";
        String[] p = host.split("\\.");
        if (p.length <= 2) return host;
        String last2 = p[p.length - 2] + "." + p[p.length - 1];
        boolean shortSecond = p[p.length - 2].length() <= 3 && p[p.length - 1].length() == 2;
        return shortSecond ? p[p.length - 3] + "." + last2 : last2;
    }
}
