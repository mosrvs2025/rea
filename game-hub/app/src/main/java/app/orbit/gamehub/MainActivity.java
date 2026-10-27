package app.orbit.gamehub;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Original local-first launcher. No Epic API, copied code or embedded credentials. */
public class MainActivity extends Activity {
    private final int bg=0xff0d111b, card=0xff1a2232, ink=0xfff3f6fc, muted=0xffa5b1c7, mint=0xffb9f871;
    private LinearLayout root, list, tabs;
    private EditText search;
    private String tab="Games", query="";
    private List<Game> games=new ArrayList<>();
    private Set<String> favorites, manual;
    private android.content.SharedPreferences prefs;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static class Game { String label,pkg; android.graphics.drawable.Drawable icon; boolean game; Game(String l,String p,android.graphics.drawable.Drawable i,boolean g){label=l;pkg=p;icon=i;game=g;} }
    @Override public void onCreate(Bundle state) { super.onCreate(state); prefs=getSharedPreferences("orbit",0); favorites=new HashSet<>(prefs.getStringSet("favorites",Collections.emptySet()));manual=new HashSet<>(prefs.getStringSet("manual",Collections.emptySet()));getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);build(); }
    @Override public void onResume(){super.onResume();load();}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private void build(){
        root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(bg);root.setPadding(dp(22),dp(12),dp(22),0);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(22),dp(12)+insets.getSystemWindowInsetTop(),dp(22),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo=text("◉  ORBIT",16,mint);logo.setTypeface(null,Typeface.BOLD);brand.addView(logo,new LinearLayout.LayoutParams(0,dp(48),1));
        TextView settings=text("About",14,muted);settings.setPadding(dp(12),dp(12),0,dp(12));settings.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Your games. Your space.").setMessage("Orbit 0.1 • Independent prototype\n\nYour library and favorites stay on this phone. No ads, accounts or analytics. Games are identified from Android metadata; use All apps to add missing games.\n\nDiscover opens official websites in your browser. Epic sign-in, purchases, entitlement sync and managed downloads are not implemented.").setPositiveButton("Got it",null).show());brand.addView(settings);root.addView(brand);
        TextView title=text("Ready when\nyou are.",38,ink);title.setTypeface(null,Typeface.BOLD);title.setPadding(0,dp(18),0,dp(10));root.addView(title);
        TextView sub=text("One home for everything you play.",15,muted);sub.setPadding(0,0,0,dp(24));root.addView(sub);
        search=new EditText(this);search.setSingleLine(true);search.setHint("Search your library");search.setTextColor(ink);search.setHintTextColor(muted);search.setTextSize(16);search.setPadding(dp(18),0,dp(18),0);search.setBackground(shape(card,16));root.addView(search,new LinearLayout.LayoutParams(-1,dp(54)));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){query=s.toString();render();}public void afterTextChanged(Editable e){}});
        HorizontalScrollView strip=new HorizontalScrollView(this);strip.setHorizontalScrollBarEnabled(false);tabs=new LinearLayout(this);tabs.setPadding(0,dp(18),0,dp(18));strip.addView(tabs);root.addView(strip);
        for(String name:new String[]{"Games","Favorites","All apps","Discover"}){Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setTextSize(13);b.setMinWidth(0);b.setMinimumWidth(0);b.setTextColor(ink);b.setBackground(shape(card,24));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(42));p.setMargins(0,0,dp(8),0);b.setPadding(dp(16),0,dp(16),0);tabs.addView(b,p);b.setOnClickListener(v->{tab=name;render();});}
        ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(1);list.setPadding(0,0,0,dp(20));scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));render();
    }
    private void load(){worker.execute(()->{ArrayList<Game> found=new ArrayList<>();PackageManager pm=getPackageManager();HashSet<String> seen=new HashSet<>();Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);for(ResolveInfo r:pm.queryIntentActivities(launcher,0)){ApplicationInfo a=r.activityInfo.applicationInfo;if(a.packageName.equals(getPackageName())||!seen.add(a.packageName))continue;found.add(new Game(r.loadLabel(pm).toString(),a.packageName,r.loadIcon(pm),a.category==ApplicationInfo.CATEGORY_GAME||(a.flags&ApplicationInfo.FLAG_IS_GAME)!=0));}found.sort((a,b)->a.label.compareToIgnoreCase(b.label));runOnUiThread(()->{if(!isDestroyed()){games=found;render();}});});}
    private void render(){if(list==null)return;list.removeAllViews();for(int i=0;i<tabs.getChildCount();i++){Button b=(Button)tabs.getChildAt(i);boolean active=b.getText().toString().equals(tab);b.setTextColor(active?bg:muted);b.setBackground(shape(active?mint:card,24));}
        if(tab.equals("Discover")){heading("Explore the official stores");store("Epic Games","Browse Epic's mobile store","https://store.epicgames.com/mobile");store("GOG","Discover DRM-free PC games","https://www.gog.com/");store("itch.io","Discover independent games","https://itch.io/");store("Steam","Browse PC games in your browser","https://store.steampowered.com/");return;}
        ArrayList<Game> visible=new ArrayList<>();for(Game g:games){boolean match=tab.equals("All apps")||tab.equals("Favorites")&&favorites.contains(g.pkg)||tab.equals("Games")&&(g.game||manual.contains(g.pkg));if(match&&g.label.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))visible.add(g);}
        heading(tab+"  ·  "+visible.size());
        if(visible.isEmpty()){TextView empty=text(query.isEmpty()?"Your next session starts here.\n\nOpen All apps and tap + beside a game to add it to your library.":"No matches. Try another name.",17,muted);empty.setPadding(0,dp(24),0,dp(20));list.addView(empty);}
        for(Game g:visible){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(14),dp(14),dp(10),dp(14));row.setBackground(shape(card,18));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,0,0,dp(10));list.addView(row,rp);ImageView icon=new ImageView(this);icon.setImageDrawable(g.icon);row.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(48)));LinearLayout copy=new LinearLayout(this);copy.setOrientation(1);copy.setPadding(dp(14),0,dp(6),0);TextView label=text(g.label,17,ink);label.setTypeface(null,Typeface.BOLD);copy.addView(label);copy.addView(text("Tap to play",12,muted));row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));row.setOnClickListener(v->launch(g));
            Button star=new Button(this);star.setText(favorites.contains(g.pkg)?"★":"☆");star.setContentDescription((favorites.contains(g.pkg)?"Unfavorite ":"Favorite ")+g.label);star.setTextColor(mint);star.setTextSize(24);star.setMinWidth(0);star.setMinimumWidth(0);star.setBackgroundColor(Color.TRANSPARENT);row.addView(star,new LinearLayout.LayoutParams(dp(48),dp(48)));star.setOnClickListener(v->{toggle(favorites,g.pkg,"favorites");render();});
            if(tab.equals("All apps")&&!g.game){Button add=new Button(this);add.setText(manual.contains(g.pkg)?"−":"+");add.setTextColor(ink);add.setContentDescription((manual.contains(g.pkg)?"Remove game ":"Add game ")+g.label);add.setMinWidth(0);add.setMinimumWidth(0);add.setBackgroundColor(Color.TRANSPARENT);row.addView(add,new LinearLayout.LayoutParams(dp(44),dp(48)));add.setOnClickListener(v->{toggle(manual,g.pkg,"manual");render();});}
        }
    }
    private void toggle(Set<String> set,String key,String name){if(!set.remove(key))set.add(key);prefs.edit().putStringSet(name,new HashSet<>(set)).apply();}
    private void heading(String s){TextView t=text(s,15,muted);t.setPadding(0,dp(8),0,dp(18));list.addView(t);}
    private void launch(Game g){Intent i=getPackageManager().getLaunchIntentForPackage(g.pkg);if(i==null){Toast.makeText(this,"App unavailable. Refreshing library.",Toast.LENGTH_SHORT).show();load();return;}try{startActivity(i);}catch(ActivityNotFoundException|SecurityException e){Toast.makeText(this,"Android could not open this app.",Toast.LENGTH_SHORT).show();}}
    private void store(String name,String detail,String url){if(!query.isEmpty()&&!name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))return;TextView t=text(name+"  ↗\n"+detail,18,ink);t.setPadding(dp(20),dp(24),dp(20),dp(24));t.setBackground(shape(card,18));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(12));list.addView(t,p);t.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(ActivityNotFoundException e){Toast.makeText(this,"Install a browser to open this store.",Toast.LENGTH_SHORT).show();}});}
    @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
