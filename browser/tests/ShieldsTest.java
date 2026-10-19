package com.zoro.rad;
import java.nio.file.*;
public class ShieldsTest {
  public static void main(String[] a) throws Exception {
    long t0=System.nanoTime();
    Shields s=new Shields();
    for (String f: new String[]{"easylist","easyprivacy","ublock","ublock-privacy","peterlowe"})
      s.addList(new String(Files.readAllBytes(Paths.get("lists/"+f+".txt")),"UTF-8"));
    s.finish();
    System.out.printf("load %.0f ms, rules %d%n",(System.nanoTime()-t0)/1e6,s.ruleCount);
    String page="https://www.espn.com/nfl/";
    String[][] cases={
      {"https://securepubads.g.doubleclick.net/tag/js/gpt.js","BLOCK"},
      {"https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js","BLOCK"},
      {"https://www.google-analytics.com/analytics.js","BLOCK"},
      {"https://connect.facebook.net/en_US/fbevents.js","BLOCK"},
      {"https://c.amazon-adsystem.com/aax2/apstag.js","BLOCK"},
      {"https://cdn.taboola.com/libtrc/espn/loader.js","BLOCK"},
      {"https://static.criteo.net/js/ld/publishertag.js","BLOCK"},
      {"https://a.espncdn.com/redesign/assets/img/logos/espn-404@2x.png","ALLOW"},
      {"https://www.espn.com/apis/site/v2/sports/football/nfl/scoreboard","ALLOW"},
      {"https://fonts.googleapis.com/css2?family=Roboto","ALLOW"},
      {"https://code.jquery.com/jquery-3.7.1.min.js","ALLOW"},
      {"https://i.ytimg.com/vi/abc/hqdefault.jpg","ALLOW"},
      {"https://www.youtube.com/s/player/abc/base.js","ALLOW"},
      {"https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg","ALLOW"},
    };
    int bad=0;
    for (String[] c: cases){ boolean b=s.shouldBlock(c[0],page); boolean ok=b==c[1].equals("BLOCK"); if(!ok)bad++;
      System.out.println((ok?"ok   ":"FAIL ")+(b?"blocked ":"allowed ")+c[0]); }
    String[] pops={"https://www.onclickads.net/xyz","https://adsterra.com/abc","https://popads.net/x"};
    for (String p:pops) System.out.println("popup "+(s.blockPopup(p,"https://somestream.example/watch")?"blocked ":"allowed ")+p);
    t0=System.nanoTime(); int n=0;
    for (int r=0;r<200;r++) for (String[] c: cases){ s.shouldBlock(c[0],page); n++; }
    System.out.printf("%.3f ms per request%n",(System.nanoTime()-t0)/1e6/n);
    String css=s.css(page);
    System.out.printf("css for espn: %d KB%n", css.length()/1024);
    System.out.println("failures: "+bad);
  }
}
