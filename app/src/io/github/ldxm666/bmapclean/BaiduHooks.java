package io.github.ldxm666.bmapclean;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

/** Baidu 22.0.0: component binding hooks. No Activity/View/TextView hooks or polling. */
public final class BaiduHooks {
    private static final String DU = "com.baidu.baidumaps.duhelper.commute.";
    private static final Map<View,Integer> rowCounts = new WeakHashMap<>();
    private static final java.util.Set<String> toolIdsLogged = new java.util.HashSet<>();
    /** 已回报给设置页的未知宫格文案（本进程内去重） */
    private static final java.util.Set<String> discoveredTools = new java.util.HashSet<>();
    private BaiduHooks() {}
    public static void install(ClassLoader cl) throws Exception {
        android.content.pm.PackageInfo version = io.github.ldxm666.mapclean.Compatibility.packageInfo(cl, MainHook.PKG_BMAP);
        H.log("baidu_adapter version=" + (version == null ? "unknown" : version.versionName) + " mode=capabilities");
        // 宿主进程 Application：给 Spec 落盘/读回「已发现宫格文案」用（见 Spec.loadDiscovered）。
        // 失败只降级为纯内存模式，不影响任何 hook。
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                Spec.attach((android.content.Context) app);
                Spec.loadDiscovered();
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "spec context unavailable: " + t.getClass().getSimpleName());
        }
        Class<?> splash = H.cls(cl, "com.baidu.baidumaps.splash.SplashAdManager");
        // 开屏闸门：断源（决定性修复）。
        //
        // ⚠ 这里曾经写成 `if (versionCode == 1650)`，等于只对 22.0.0 生效。
        // 22.0.40 / 22.0.50 的 versionCode 是 1655，整段被跳过 → 开屏广告照跑 ——
        // 这就是「更新新版后又冒出来的开屏广告」的真正根因：不是漏了哪家 SDK，
        // 而是闸门被一个版本号判断挡在门外。
        //
        // F()/y()/w()/z() 是纯查询，强制 false 后宿主走自家「没有广告」分支
        // （WelcomeScreen.t() 在第 43 字节直接 return-void），既不显示广告、
        // 也不会等待任何回调 —— 22.0.0 与 22.0.50 的 t() 反汇编逐字节一致，
        // 四个方法名与签名两代都在（classes13.dex）。
        //
        // 不加版本判断：方法找不到时 hook() 自己记 miss，不会误伤别的版本。
        for (String name : new String[]{"F", "z", "w", "y"}) {
            hook(splash, name, chain -> Boolean.FALSE);
        }
        // 22.0.50 新增的兜底闸门：SplashAdManager 还有 D()/N()/O()/q()/t()/u()
        // 一族布尔查询。只挂**无参返回 boolean** 的重载，语义同样是纯查询。
        hookSplashGates(splash);

        // ---- 广告 SDK 懒挂载 ----
        // 反编译实证 G()/H() 的调用者只有三处，全在开屏链路上：
        //   WelcomeScreen.t() / HomeSplashPresenter.showSDKSplashScreen$
        //   SplashAdManager.H()
        // 闸门已在 t() 上把它们挡下；万一某版闸门漏了一个分支，G() 一旦真的
        // 被调用，说明此刻 loader 类已经加载完成 —— 就在这里把 SDK 全部挂上，
        // 比在 onPackageReady 早期挂可靠（早期 cls() 全 miss，实测 loaders=0）。
        hookAdSdkOnRequest(splash, cl);
        BaiduAdHooks.install(cl);
        BaiduPoiLayer.install(cl);
        BaiduSearchAds.install(cl);
        BaiduPanelHeight.install(cl);
        // BMAd 开放封装层在这里就挂（provider 类在 install 时已能解析，实测 voided=2）；
        // commonadprovider 的 ADN loader 走上面的懒挂载，见 hookAdSdkOnRequest。
        BaiduSdkBlock.installBmad(cl);

        // UI half runs in the same pass: deferring it moved the cost into the first-frame
        // window instead of removing it (measured worse), so keep the single install pass.
        installUi(cl);
    }

    /** UI-layer hooks, installed shortly after the process is up (see install()). */
    private static void installUi(ClassLoader cl) throws Exception {
        // The fixed search bar remains reachable when the entire bottom bar is hidden.
        Class<?> searchEntry=H.cls(cl,"com.baidu.baidumaps.aihome.search.DefaultSearchUpUIComponent");
        hook(searchEntry,"getView",chain -> {
            Object result=chain.proceed();
            if(result instanceof View)io.github.ldxm666.mapclean.EmbeddedSettings.bindEntry((View)result,MainHook.PKG_BMAP);
            applySearchChips(chain.getThisObject());
            return result;
        });
        hook(searchEntry,"onResume",chain -> {
            Object result=chain.proceed();
            dumpSearchBinding(chain.getThisObject());   // TEMP probe
            applySearchChips(chain.getThisObject());
            if(io.github.ldxm666.mapclean.EmbeddedSettings.enabledFor(MainHook.PKG_BMAP)) {
                Object binding=field(chain.getThisObject(),"binding");
                if(binding!=null)for(String slot:new String[]{"commonSearchBoxHome","searchBoxAnim"}) {
                    Object entry=field(binding,slot);
                    if(entry instanceof View)io.github.ldxm666.mapclean.EmbeddedSettings.bindEntry((View)entry,MainHook.PKG_BMAP);
                }
            }
            return result;
        });
        Class<?> mainCard = H.cls(cl, DU + "DuMainCardUIComponent");
        hook(mainCard, "applyCardVisibility", chain -> Spec.effectiveVisible(Spec.K_AI_NOW, Spec.HOOK_READ)
            ? chain.proceed() : chain.proceed(new Object[]{false}), boolean.class);
        Class<?> panel = H.cls(cl, "com.baidu.mapframework.aihome.AIHomePanel");
        hook(panel, "getAiHomePanel", chain -> {
            Object result = chain.proceed();
            tabs(chain.getThisObject()); return result;
        }, Context.class, String.class);
        hook(panel, "updateOtherTab", chain -> {
            Object result = chain.proceed(); tabs(chain.getThisObject()); return result;
        });
        Class<?> home=H.cls(cl,"com.baidu.baidumaps.aihome.panel.HomeUIComponent");
        // 面板能上拉多高。原来只在首页**全空**时才归零；只关掉推荐 / 天气时它照样返回整个
        // 面板的高度，上滑就拉出一片空白。现在夹到"剩余可见内容"的高度（见 HomeClean.clampPeek）。
        hook(home,"calcBasePeekHeight",chain -> {
            if(emptyHome())return 0;
            return io.github.ldxm666.bmapclean.HomeClean.clampPeek((Integer)chain.proceed());
        },android.content.res.Resources.class,int.class);
        hook(home,"adjustPeekHeightByState",chain -> {
            if(emptyHome())return 0;
            return io.github.ldxm666.bmapclean.HomeClean.clampPeek((Integer)chain.proceed());
        },H.cls(cl,"com.baidu.baidumaps.aihome.newhome.CollapsePanelState"),int.class,int.class,android.content.res.Resources.class);
        for(String name:new String[]{"addUfoView","onResume"})hook(home,name,chain -> {
            Object r=chain.proceed();tabsBinding(field(chain.getThisObject(),"ufoBinding"));
            BaiduTabRouting.refreshComponent(chain.getThisObject());
            if(!Cfg.visible(Spec.K_BAR))hide((View)field(chain.getThisObject(),"ufoContainer"));return r;
        });
        Class<?> tabs=H.cls(cl,"com.baidu.baidumaps.aihome.panel.presenter.HomeTabPresenter");
        for(String name:new String[]{"init","initListeners","initChangeableTab","refreshSkin","bindClickOnTabChanged","refreshUfoWidgetEvent","onResume"})hook(tabs,name,chain -> {
            Object r=chain.proceed();tabsBinding(field(field(chain.getThisObject(),"component"),"ufoBinding"));
            BaiduTabRouting.refreshPresenter(chain.getThisObject());return r;
        });
        // Only this presenter's native bottom-tab taxi route is gated. The
        // separate route/tool/search entry points keep their own navigation.
        hook(tabs,"jumpRentCarPage",chain -> Cfg.visible(Spec.K_TAB+Spec.TABS[3])?chain.proceed():null);
        Class<?> weather=H.cls(cl,"com.baidu.mapframework.common.mapview.action.WeatherAction");
        for(String name:new String[]{"updateView","onStateCreate","showWeather","deactivateForceGone"})hook(weather,name,chain -> {
            Object r=chain.proceed();
            if(!Spec.effectiveVisible(Spec.K_WX_MAP,Spec.HOOK_READ)){
                weather.getField("forceGone").setBoolean(chain.getThisObject(),true);
                hide((View)field(chain.getThisObject(),"weatherLimitedContainer"));
            }
            return r;
        });
        component(cl, "RoutePanelShortCutUIComponent", Spec.K_TOOLS);
        component(cl, "CAAHCUIComponent", Spec.K_HC);
        Class<?> feed=H.cls(cl,DU+"FeedUIComponent");
        hook(feed,"getView",chain -> {
            View v=(View)chain.proceed();
            if(!Spec.effectiveVisible(Spec.K_FEED_QUALITY,Spec.HOOK_READ)
                &&!Spec.effectiveVisible(Spec.K_FEED_CHIPS,Spec.HOOK_READ)
                &&!Spec.effectiveVisible(Spec.K_WX_CARD,Spec.HOOK_READ))hide(v);
            return v;
        });
        Class<?> route=H.cls(cl,"com.baidu.baidumaps.duhelper.aihome.RouteUIComponent");
        // Native registration is the authoritative path; suppress hidden components before construction.
        hook(H.cls(cl,"com.baidu.baidumaps.duhelper.aihome.RouteUIComponent$l"),"g",chain -> {
            String id=(String)chain.getArg(0);
            boolean hide="du_trip_entrance".equals(id)?!Cfg.visible(Spec.K_TOOLS)
                :"du_trip_address".equals(id)?!Cfg.visible(Spec.K_HC)
                :"du_trip_main_card".equals(id)?!Spec.effectiveVisible(Spec.K_AI_NOW, Spec.HOOK_READ)
                :"du_aide_feed".equals(id)?!Spec.effectiveVisible(Spec.K_FEED_QUALITY,Spec.HOOK_READ)
                    &&!Spec.effectiveVisible(Spec.K_FEED_CHIPS,Spec.HOOK_READ)
                    &&!Spec.effectiveVisible(Spec.K_WX_CARD,Spec.HOOK_READ):false;
            return hide?null:chain.proceed();
        },String.class);
        for(String name:new String[]{"onCreateView","onResume","updateToCollapsedUI","updateToExpandUI"})hook(route,name,chain -> {
            Object r=chain.proceed();Object b=field(chain.getThisObject(),"binding");
            if(b!=null){
                if(!Spec.effectiveVisible(Spec.K_AI_NOW, Spec.HOOK_READ))hide((View)field(b,"componentContainer1"));
                if(!Cfg.visible(Spec.K_TOOLS))hide((View)field(b,"componentContainer3"));
                if(!Cfg.visible(Spec.K_HC))hide((View)field(b,"componentContainer5"));
                if(!Spec.effectiveVisible(Spec.K_FEED_QUALITY,Spec.HOOK_READ)
                    &&!Spec.effectiveVisible(Spec.K_FEED_CHIPS,Spec.HOOK_READ)
                    &&!Spec.effectiveVisible(Spec.K_WX_CARD,Spec.HOOK_READ))hide((View)field(b,"componentContainer6"));
            }return r;
        });
        Class<?> address = H.cls(cl, DU + "CAAHCUIComponent");
        for (String name : new String[]{"initView","viewLocalShow","initBg","selfUpdate"}) {
            hook(address,name,chain -> { Object r=chain.proceed(); address(chain.getThisObject()); return r; });
        }
        Class<?> row = H.cls(cl, "com.baidu.baidumaps.duhelper.view.ShortCutRow");
        hook(row, "update", chain -> {
            List<?> source = (List<?>) chain.getArg(0);
            List<Object> selected = new ArrayList<>();
            for (Object model : source) {
                Object unit = field(model,"d"), label = field(field(unit,"b"),"a");
                String title = label instanceof String ? (String) label : "";
                if (toolVisible(title)) selected.add(model);
                String identity=field(model,"m")+":"+title;
                if(toolIdsLogged.add(identity))H.log("tool model "+identity);
            }
            Object[] args = chain.getArgs().toArray(); args[0] = selected;
            Object r = chain.proceed(args);
            View view = (View) chain.getThisObject();
            rowCounts.put(view, selected.size()); layoutRow(view, selected.size()); return r;
        },List.class,int[].class,String[].class,int.class);
        hook(row,"onSlide",chain -> {
            Integer count=rowCounts.get((View)chain.getThisObject());
            return count!=null && count<5 ? null : chain.proceed();
        },float.class);
        // Feed data and user-center card bindings are handled in their own Talos bundle only.
        BaiduTalos.install(cl);
    }
    private static boolean toolVisible(String title) {
        if (!Cfg.visible(Spec.K_TOOLS)) return false;
        if ("公交地铁".equals(title)) title="公共交通";
        for(String known:Spec.toolLabels()) if(known.equals(title)) return Cfg.visible(Spec.K_TOOL+known);
        // 静态清单里没有的文案：保持可见（失效安全），同时回报给设置页补一个开关。
        reportUnknownTool(title);
        return true;
    }
    /**
     * 把静态清单里没有的宫格文案回报给设置页（App 侧 → {@link Spec#mergeDiscovered}）。
     * 只回报未知文案；同一进程内按文案去重，避免每次 update 都发广播。
     */
    static void reportUnknownTool(String label) {
        if (label == null || label.length() == 0 || label.length() > 12) return;
        if (!discoveredTools.add(label)) return;
        H.log("toolGrid discovered new label: " + label);
        try {
            Report.send(null, "tools", label, true);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "toolGrid report failed: " + t);
        }
    }
    private static boolean emptyHome(){
        return !Cfg.visible(Spec.K_TOOLS)&&!Cfg.visible(Spec.K_HC)
            &&!Spec.effectiveVisible(Spec.K_FEED_CHIPS,Spec.HOOK_READ)
            &&!Spec.effectiveVisible(Spec.K_FEED_QUALITY,Spec.HOOK_READ)
            &&!Spec.effectiveVisible(Spec.K_WX_CARD,Spec.HOOK_READ)
            &&!Spec.effectiveVisible(Spec.K_AI_NOW,Spec.HOOK_READ);
    }
    private static void component(ClassLoader cl,String name,String key) {
        Class<?> c=H.cls(cl,DU+name);
        hook(c,"getView",chain -> {
            View view=(View)chain.proceed();
            if(view!=null && !Cfg.visible(key)) view.setVisibility(View.GONE);
            return view;
        });
    }
    private static void address(Object self) throws Exception {
        Object binding=field(self,"mBinding");
        if(binding==null) return;
        if(!Cfg.visible(Spec.K_HC)) { hide((View)invoke(binding,"getRoot")); return; }
        if(!Cfg.visible(Spec.K_HC_HOME)) hide((View)field(self,"homeInfo"));
        if(!Cfg.visible(Spec.K_HC_COMPANY)) hide((View)field(self,"companyInfo"));
        if(!Cfg.visible(Spec.K_HC_SETTING)) {
            hide((View)field(binding,"settingContainer"));
            Field width=self.getClass().getDeclaredField("settingContainerWidth");width.setAccessible(true);width.setInt(self,0);
            View content=(View)field(binding,"containerBesidesTitle");
            if(content!=null && content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp=(ViewGroup.MarginLayoutParams)content.getLayoutParams();
                if(lp.rightMargin!=0){lp.rightMargin=0;content.setLayoutParams(lp);}
            }
        }
    }
    private static void tabs(Object panel) throws Exception {
        Object binding=invoke(panel,"getAihomeBinding");
        tabsBinding(binding);
    }
    private static void tabsBinding(Object binding) throws Exception {
        if(binding==null)return;
        LinearLayout bar=(LinearLayout)field(binding,"newBtns");
        if(bar==null)return;
        boolean master=Cfg.visible(Spec.K_BAR);
        // Keep one reachable tab when the bar is shown.
        String[] slots={"newRoute","newNearby","newThird","newFourth","newUser"};
        int n=0;
        for(int i=0;i<slots.length;i++) {
            View cell=(View)field(binding,slots[i]);
            boolean show=master && (i==0 || Cfg.visible(Spec.K_TAB+Spec.TABS[i]));
            if(cell==null)continue;
            if(i==0)io.github.ldxm666.mapclean.EmbeddedSettings.bindEntry(cell,MainHook.PKG_BMAP);
            if(i==slots.length-1 && io.github.ldxm666.mapclean.EmbeddedSettings.enabledFor(MainHook.PKG_BMAP)) {
                cell.setOnLongClickListener(view -> {
                    android.content.Context context=view.getContext();
                    for(int depth=0;depth<16 && context!=null;depth++) {
                        if(context instanceof android.app.Activity)
                            return io.github.ldxm666.mapclean.EmbeddedSettings.open((android.app.Activity)context);
                        if(!(context instanceof android.content.ContextWrapper))break;
                        android.content.Context base=((android.content.ContextWrapper)context).getBaseContext();
                        if(base==context)break;
                        context=base;
                    }
                    return false;
                });
            }
            if(!show)hide(cell);
            if(cell.getVisibility()==View.VISIBLE){
                n++; LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)cell.getLayoutParams();
                if(lp.width!=0||lp.weight!=1){lp.width=0;lp.weight=1;cell.setLayoutParams(lp);}
            }
        }
        if(bar.getWeightSum()!=n)bar.setWeightSum(n);
        if(!master){hide(bar);hide((View)invoke(binding,"getRoot"));}
        if(!master||!Cfg.visible(Spec.K_TAB+Spec.TABS[2]))hide((View)field(binding,"homeAiContainer"));
    }
    private static void layoutRow(View row,int count) throws Exception {
        Object cells=field(row,"frameLayouts");if(!(cells instanceof List))return;
        List<?> list=(List<?>)cells;
        int width=row.getWidth();if(width<=0) width=row.getResources().getDisplayMetrics().widthPixels;
        for(int i=0;i<list.size();i++){
            View cell=(View)list.get(i);
            if(i>=count){hide(cell);continue;}
            cell.setVisibility(View.VISIBLE);cell.setAlpha(1f);cell.setTranslationX(0);
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)cell.getLayoutParams();
            int w=Math.max(1,width/Math.max(1,count));
            if(lp.width!=w||lp.leftMargin!=i*w){lp.width=w;lp.leftMargin=i*w;cell.setLayoutParams(lp);}
        }
        if(count==0)hide(row);else row.setVisibility(View.VISIBLE);
    }
    static void hide(View view){if(view!=null&&view.getVisibility()!=View.GONE)view.setVisibility(View.GONE);}
    static Object field(Object target,String name) throws Exception {
        if(target==null)return null;
        for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())try {
            Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(target);
        }catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(target.getClass().getName()+"."+name);
    }
    static Object invoke(Object target,String name) throws Exception {
        if(target==null)return null;Method m=target.getClass().getMethod(name);return m.invoke(target);
    }
    static void hook(Class<?> c,String name,XposedInterface.Hooker hk,Class<?>...args){
        Method m=null;try{if(c!=null)m=c.getDeclaredMethod(name,args);}catch(Throwable ignored){}
        H.hook(m,"baidu_"+name+(c==null?"":c.getSimpleName()),hk);
    }

    /**
     * 广告 SDK 的<b>懒挂载触发器</b>：挂在 {@code SplashAdManager.G} / {@code H} 上。
     *
     * <p><b>为什么不在 onPackageReady 里直接挂。</b>实测：install 阶段
     * {@code H.cls(cl, "…commonadprovider.gromore.GromoreAdLoader")} 全部返回 null ——
     * 那时 SDK 还没被 dex 加载进来，{@code ClassLoader.classes} 里也枚举不到，
     * 结果 {@code commonadprovider_loaders=0}，等于一层防线完全没建起来。
     *
     * <p>{@code G()} / {@code H()} 是 SDK 广告请求的真实入口（反编译实证调用者只有
     * 三处，全在开屏链路上）。它们被调用 ⇒ 此刻 loader 类必然已加载 ⇒ 在这时挂才挂得上。
     * 钩子本身 {@code observe} 原样放行，不改变广告请求行为，只负责补挂载。
     *
     * <p>如果闸门全都生效（正常情况），{@code G()} 根本不会被调用，此处一次也不触发，
     * 零开销 —— 它纯粹是“闸门漏了”时的第二道防线。
     */
    private static void hookAdSdkOnRequest(Class<?> splash, final ClassLoader cl) {
        if (splash == null) return;
        final java.util.concurrent.atomic.AtomicBoolean armed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        XposedInterface.Hooker lazy = new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    if (armed.compareAndSet(false, true)) {
                        int n = BaiduSdkBlock.installLoaders(cl);
                        H.log("baidu_adn lazy-armed loaders=" + n);
                    }
                } catch (Throwable ignored) { }
                return chain.proceed();
            }
        };
        Method[] all;
        try {
            all = splash.getDeclaredMethods();
        } catch (Throwable t) {
            return;
        }
        for (int i = 0; i < all.length; i++) {
            Method m = all[i];
            String n = m.getName();
            if (!"G".equals(n) && !"H".equals(n)) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 2) continue;
            if (!android.content.Context.class.isAssignableFrom(ps[0])) continue;
            H.hook(m, "baidu_adreq_" + n + i, lazy);
        }
        // 保底补挂：闸门全生效时 G() 永不被调用，loader 会一直没挂上。
        // 主 Activity onResume 是“SDK 一定已加载完成”的最早可靠时刻，
        // 且每个进程只会发生几次，代价可忽略。
        hookLazyArmOnFirstResume(armed, cl);
    }

    /**
     * 第一次任意 {@code Activity.onResume} 时补挂一次 SDK。
     *
     * <p>为什么选 onResume 而不是 postDelayed：postDelayed 的“多久算够”全靠猜 ——
     * 网络慢、冷启动慢都会让 SDK 加载晚于定时器，于是又回到 0。
     * onResume 是语义上“宿主已经把自己的 Application/首页跑起来”的时刻，
     * 此刻再枚举 ClassLoader 必然能看到全部 SDK。
     */
    private static void hookLazyArmOnFirstResume(
            final java.util.concurrent.atomic.AtomicBoolean armed, final ClassLoader cl) {
        try {
            Method onResume = android.app.Activity.class.getDeclaredMethod("onResume");
            H.hook(onResume, "baidu_adn_arm_resume", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        if (armed.compareAndSet(false, true)) {
                            int n = BaiduSdkBlock.installLoaders(cl);
                            H.log("baidu_adn armed on first resume loaders=" + n);
                        }
                    } catch (Throwable ignored) { }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            H.log(Log.WARN, "BMapClean", "arm-on-resume hook failed: " + t);
        }
    }

    /**
     * 开屏闸门兜底：把 {@code SplashAdManager} 里<b>所有</b>无参返回 boolean 的
     * 静态查询方法一律钉成 false。
     *
     * <p><b>为什么需要这一层。</b>22.0.50 的 SplashAdManager 有 43 个方法，除
     * F/y/w/z 之外还有 D()/N()/O()/q()/t()/u() 等一串布尔查询。百度在两代之间
     * 重排过单字母方法名（见 BMAdSplashProvider 的 x() 在 22.0.50 消失），
     * 只认四个名字迟早再漏 —— 这一层按签名形状兜住，新名字自动纳入。
     *
     * <p>为什么安全：只挑「static + 无参 + 返回 boolean」，这一族在反编译里全是
     * 纯查询（读 SP / 读静态布尔），没有回调、没有写操作。WelcomeScreen.t()
     * 的六个 return-void 短路点全部落在这一类上，强制 false 走的是应用自己
     * “无广告”的原生分支。
     *
     * <p>性能：Scan 一次 43 个方法，只在 onPackageReady 跑一遍。
     */
    private static void hookSplashGates(Class<?> splash) {
        if (splash == null) return;
        Method[] all;
        try {
            all = splash.getDeclaredMethods();
        } catch (Throwable t) {
            return;
        }
        int n = 0;
        for (int i = 0; i < all.length; i++) {
            Method m = all[i];
            if (m.getParameterTypes().length != 0) continue;              // 只认无参
            Class<?> rt = m.getReturnType();
            if (rt != boolean.class && rt != Boolean.class) continue;     // 只认布尔返回
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            try {
                H.hook(m, "baidu_splash_gate_" + m.getName() + i,
                        chain -> Boolean.FALSE);
                n++;
            } catch (Throwable ignored) { }
        }
        H.log("baidu splash gates observed=" + n);
    }

    /**
     * 搜索框下面那排快捷入口（美食 / 加油站 / 景点 / 厕所 / 酒店…）。
     *
     * <p><b>为什么不用文案锚点。</b>先试过"同一容器命中 ≥3 条文案"，真机上完全不命中：这排是
     * Talos（Yoga 自绘）出的，文本不在 View 树里，`subtreeHasExactText` 抓不到。
     *
     * <p><b>真正的把手来自 DEX。</b>百度搜索区的 binding 里有一组具名槽位
     * （`searchBoxLine1` = 输入框那一行，`searchBoxLine2` = 它下面那一行，另有
     * `searchBoxCard` / `searchBoxLine`），字段名是 `searchBoxBinding`。第二行就是那排快捷入口，
     * 所以直接按槽位隐藏，不再猜结构。
     */
    private static void applySearchChips(Object component) {
        // 开关语义是"显示"，默认开 —— 只有用户主动关掉才会去动它。
        if (Spec.effectiveVisible(Spec.K_SEARCH_CHIPS, Spec.HOOK_READ)) return;
        try {
            Object binding = field(component, "searchBoxBinding");
            if (binding == null) binding = field(component, "binding");
            if (binding == null) return;
            for (String slot : new String[]{"searchBoxLine2", "searchBoxLine", "searchBoxCard"}) {
                Object v = field(binding, slot);
                if (v instanceof View) hide((View) v);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * TEMP 诊断：把组件与 binding 的**真实字段名**打进 logcat。
     *
     * 前两次我按推测的字段名（binding / searchBoxBinding）和槽位名去隐藏，都没落地 ——
     * 这次直接把运行时真实的字段名和值类型打出来，不再猜。确认后这段会删掉。
     */
    private static boolean sProbed;

    private static void dumpSearchBinding(Object component) {
        if (sProbed) return;
        sProbed = true;
        try {
            StringBuilder sb = new StringBuilder("probe component=" + component.getClass().getName() + " fields: ");
            for (java.lang.reflect.Field f : component.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                sb.append(f.getName()).append('=');
                try {
                    Object v = f.get(component);
                    sb.append(v == null ? "null" : v.getClass().getSimpleName());
                } catch (Throwable t) { sb.append('?'); }
                sb.append(" | ");
            }
            H.log(Log.INFO, MainHook.TAG, sb.toString());

            Object binding = field(component, "searchBoxBinding");
            if (binding == null) binding = field(component, "binding");
            if (binding == null) {
                H.log(Log.INFO, MainHook.TAG, "probe: binding NOT FOUND by either name");
                return;
            }
            StringBuilder sb2 = new StringBuilder("probe binding[" + binding.getClass().getName() + "] fields: ");
            for (java.lang.reflect.Field f : binding.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                sb2.append(f.getName()).append('=');
                try {
                    Object v = f.get(binding);
                    sb2.append(v == null ? "null" : v.getClass().getSimpleName());
                    if (v instanceof View) sb2.append("(vis=").append(((View) v).getVisibility()).append(')');
                } catch (Throwable t) { sb2.append('?'); }
                sb2.append(" | ");
            }
            H.log(Log.INFO, MainHook.TAG, sb2.toString());

            // 真实视图树：search-up 这一层到底长什么样
            try {
                Object view = component.getClass().getMethod("getView").invoke(component);
                if (view instanceof View) TreeDump.dump((View) view, "searchup");
            } catch (Throwable t) {
                H.log(Log.WARN, MainHook.TAG, "probe getView failed: " + t);
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "probe failed: " + t);
        }
    }
}
