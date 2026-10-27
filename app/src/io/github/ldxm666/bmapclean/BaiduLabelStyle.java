package io.github.ldxm666.bmapclean;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Native STY level tables: identify point icons/text from the host's own drawing
 * definitions, retain feature identities and non-point drawing references.
 * Hidden references use the host's existing uint32 -1 sentinel. Native readers
 * expect a draw array for every feature; an empty array is not an equivalent rule.
 * Definition, geometry, palette and unknown fields remain unchanged.
 *
 * <p><b>22.1.0 起支持按分类隐藏。</b>每个绘制定义里带一个图标资源名（field 4，
 * 例如 {@code MapRes/meishi}、{@code MapRes/jiudian}），{@link #filterByCategories}
 * 用它给 feature 归类，再决定藏不藏。这样「只留地铁、关掉餐饮」是可行的，
 * 而 {@link #hideAll} 那个一刀切保留下来当总开关用。
 */
final class BaiduLabelStyle {
    private static final long NO_DRAW=0xffffffffL;
    static final class Result {
        final byte[] bytes;
        final int levels, features, drawings;
        Result(byte[] bytes, int levels, int features, int drawings) {
            this.bytes=bytes; this.levels=levels; this.features=features; this.drawings=drawings;
        }
    }
    /** 一条判定：给定 feature id 与它引用的点图标名，返回是否隐藏。 */
    interface Rule { boolean hide(int feature, Set<String> icons); }

    private static final Set<Integer> PRESERVED = preserved();
    private static Set<Integer> preserved() {
        Set<Integer> out=new HashSet<Integer>();
        for (int id:BaiduLabelSettings.PRESERVED_FEATURES) out.add(id);
        return Collections.unmodifiableSet(out);
    }
    static Set<Integer> hiddenFeatures(int mask) {
        Set<Integer> out=new HashSet<Integer>();
        if ((mask&1)!=0) for (int id:BaiduLabelSettings.FEATURES[0]) out.add(id);
        return out;
    }
    static byte[] filter(byte[] source, final Set<Integer> hidden) {
        if (hidden.isEmpty()) return source;
        return transform(source, new Rule() {
            @Override public boolean hide(int feature, Set<String> icons) { return hidden.contains(feature); }
        }).bytes;
    }
    /** 一刀切：所有点绘制都隐藏（PRESERVED 里的地名/路名除外）。总开关用。 */
    static Result hideAll(byte[] source) { return transform(source, (Rule) null); }

    /**
     * 按分类隐藏。
     *
     * @param hiddenCats 要隐藏的分类 key 集合（{@link BaiduLabelSettings#CATS} 的 key
     *                   加上 {@link BaiduLabelSettings#KEY_OTHER}）。空集合 = 一类都不藏。
     */
    static Result filterByCategories(byte[] source, final Set<String> hiddenCats) {
        return transform(source, new Rule() {
            @Override public boolean hide(int feature, Set<String> icons) {
                if (hiddenCats == null || hiddenCats.isEmpty()) return false;
                if (icons.isEmpty()) return hiddenCats.contains(BaiduLabelSettings.KEY_OTHER);
                for (String icon:icons) {
                    if (hiddenCats.contains(BaiduLabelSettings.categoryOf(icon))) return true;
                }
                return false;
            }
        });
    }

    private static Result transform(byte[] source, Rule rule) {
        if (source==null || source.length>16*1024*1024) throw bad("Invalid STY size");
        Cursor root=new Cursor(source);
        Set<Long> points=new HashSet<Long>();
        Map<Long,Integer> kinds=new HashMap<Long,Integer>();
        Map<Long,String> icons=new HashMap<Long,String>();
        int definitions=0, levels=0;
        while (root.next()) {
            if (root.number==2 && root.wire==2) levels++;
            if (root.number==3 && root.wire==2) {
                definitions++;
                Cursor draws=new Cursor(root.payload());
                while (draws.next()) {
                    if (draws.wire!=2 || draws.number<1 || draws.number>7) continue;
                    Cursor style=new Cursor(draws.payload());
                    Long identity=null;
                    String icon=null;
                    while (style.next()) {
                        if (style.number==1 && style.wire==0) {
                            if (identity!=null) throw bad("Duplicate STY drawing identity");
                            identity=style.value;
                        } else if (style.number==4 && style.wire==2 && icon==null) {
                            // 图标资源名（MapRes/xxx）。不是每个绘制都有，没有就留 null。
                            try { icon=new String(style.payload(),"UTF-8"); }
                            catch (java.io.UnsupportedEncodingException ignored) { }
                        }
                    }
                    if (identity==null) throw bad("Missing STY drawing identity");
                    Integer previous=kinds.put(identity,draws.number);
                    if (previous!=null && previous.intValue()!=draws.number) throw bad("Conflicting STY drawing type");
                    if (draws.number==1 || draws.number==2) {
                        points.add(identity);
                        if (icon!=null && icon.length()>0) icons.put(identity,icon);
                    }
                }
            }
        }
        if (definitions!=1 || levels<1 || levels>64) throw bad("Unsupported STY scene schema");
        int[] counts={0,0};
        root=new Cursor(source);
        ByteArrayOutputStream out=new ByteArrayOutputStream(source.length);
        while (root.next()) {
            if (root.number==2 && root.wire==2) message(out,2,filterLevel(root.payload(),points,icons,rule,counts));
            else root.copy(out);
        }
        return new Result(counts[1]==0?source:out.toByteArray(),levels,counts[0],counts[1]);
    }
    private static byte[] filterLevel(byte[] source, Set<Long> points, Map<Long,String> icons, Rule rule, int[] counts) {
        Cursor level=new Cursor(source);
        ByteArrayOutputStream out=new ByteArrayOutputStream(source.length);
        while (level.next()) {
            if (level.number==1 && level.wire==2) {
                byte[] original=level.payload(), filtered=filterRule(original,points,icons,rule,counts);
                if (filtered==original) level.copy(out); else message(out,1,filtered);
            } else level.copy(out);
        }
        return out.toByteArray();
    }
    private static byte[] filterRule(byte[] source, Set<Long> points, Map<Long,String> icons, Rule rule, int[] counts) {
        Cursor scan=new Cursor(source);
        Integer feature=null;
        Set<String> names=new HashSet<String>();
        while (scan.next()) {
            if (scan.number==1 && scan.wire==0) {
                if (feature!=null || scan.value<0 || scan.value>0xffffffffL) throw bad("Invalid STY feature identity");
                feature=(int)scan.value;
            } else if (scan.number==2) {
                // 先把这条规则引用的点图标名收齐，供分类判定。
                if (scan.wire==0) collectName(scan.value,points,icons,names);
                else if (scan.wire==2) {
                    Cursor values=new Cursor(scan.payload());
                    while (values.pos<values.bytes.length) collectName(values.varint(),points,icons,names);
                }
            }
        }
        if (feature==null) throw bad("Missing STY feature identity");
        if (PRESERVED.contains(feature)) return source;
        // rule == null ⇒ 一刀切（隐藏所有点）。否则交给分类判定。
        if (rule!=null && !rule.hide(feature,names)) return source;
        Cursor ruleCursor=new Cursor(source);
        ByteArrayOutputStream out=new ByteArrayOutputStream(source.length);
        int removed=0;
        while (ruleCursor.next()) {
            if (ruleCursor.number==2 && ruleCursor.wire==0 && points.contains(ruleCursor.value)) {
                removed++;
                writeVarint(out,2<<3); writeVarint(out,NO_DRAW);
            }
            else if (ruleCursor.number==2 && ruleCursor.wire==2) {
                byte[] packed=ruleCursor.payload();
                Cursor values=new Cursor(packed);
                ByteArrayOutputStream keep=new ByteArrayOutputStream(packed.length);
                int before=removed;
                while (values.pos<packed.length) {
                    int start=values.pos;
                    long value=values.varint();
                    if (points.contains(value)) { removed++; writeVarint(keep,NO_DRAW); }
                    else keep.write(packed,start,values.pos-start);
                }
                if (before==removed) ruleCursor.copy(out);
                else if (keep.size()!=0) message(out,2,keep.toByteArray());
            } else ruleCursor.copy(out);
        }
        if (removed==0) return source;
        counts[0]++; counts[1]+=removed;
        return out.toByteArray();
    }
    private static void collectName(long identity, Set<Long> points, Map<Long,String> icons, Set<String> into) {
        if (!points.contains(identity)) return;
        String icon=icons.get(identity);
        if (icon!=null) into.add(icon);
    }
    private static void message(ByteArrayOutputStream out, int number, byte[] data) {
        writeVarint(out,(number<<3)|2); writeVarint(out,data.length); out.write(data,0,data.length);
    }
    private static void writeVarint(ByteArrayOutputStream out, long value) {
        do { int b=(int)(value&127); value>>>=7; out.write(value==0?b:b|128); } while(value!=0);
    }
    private static IllegalArgumentException bad(String why) { return new IllegalArgumentException(why); }
    private static final class Cursor {
        final byte[] bytes;
        int pos, start, payloadStart, payloadSize, number, wire;
        long value;
        Cursor(byte[] bytes) { this.bytes=bytes; }
        long varint() {
            long value=0;
            for (int i=0;i<10;i++) {
                if (pos>=bytes.length) throw bad("Truncated STY varint");
                int b=bytes[pos++]&255;
                if (i==9 && b>1) throw bad("Overflowed STY varint");
                value|=(long)(b&127)<<(i*7);
                if (b<128) return value;
            }
            throw bad("Invalid STY varint");
        }
        boolean next() {
            if (pos==bytes.length) return false;
            start=pos;
            long tag=varint();
            if (tag<=0 || tag>0xffffffffL || (tag>>>3)>0x1fffffffL) throw bad("Invalid STY field");
            number=(int)(tag>>>3); wire=(int)(tag&7);
            if (number==0) throw bad("Invalid STY field number");
            if (wire==0) value=varint();
            else if (wire==2) {
                long size=varint();
                if (size<0 || size>bytes.length-pos) throw bad("Truncated STY payload");
                payloadStart=pos; payloadSize=(int)size; pos+=payloadSize;
            } else if (wire==1 || wire==5) {
                int size=wire==1?8:4;
                if (size>bytes.length-pos) throw bad("Truncated STY fixed field");
                pos+=size;
            } else throw bad("Unsupported STY wire type");
            return true;
        }
        byte[] payload() { return java.util.Arrays.copyOfRange(bytes,payloadStart,payloadStart+payloadSize); }
        void copy(ByteArrayOutputStream out) { out.write(bytes,start,pos-start); }
    }
    private BaiduLabelStyle() {}
}
