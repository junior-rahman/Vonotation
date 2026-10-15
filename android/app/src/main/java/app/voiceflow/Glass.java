package app.voiceflow;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Translucent native surfaces. No screen capture, dimming, or global backdrop blur. */
final class Glass {
    static final int LIGHT=0,DARK=1,ACTIVE=2,PEARL=3,DAYBOOK=4,DAYBOOK_ACTION=5;
    static Drawable surface(Context context,float radius,int tone){return new Surface(context,radius,tone);}
    private static final class Surface extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF face=new RectF();
        private final Path clip=new Path();
        private final float corner,stroke;
        private final int tone;
        private final int warmSurface,warmSoft,warmLine,warmAction;
        private float radius;
        private Shader fill,rim,sheen;
        private int alpha=255;
        Surface(Context context,float corner,int tone){float density=context.getResources().getDisplayMetrics().density;this.corner=corner*density;stroke=density;this.tone=tone;warmSurface=Daybook.surface(context);warmSoft=Daybook.soft(context);warmLine=Daybook.line(context);warmAction=Daybook.action(context);}
        @Override protected void onBoundsChange(Rect bounds){
            face.set(bounds.left+stroke/2,bounds.top+stroke/2,bounds.right-stroke/2,bounds.bottom-stroke/2);radius=Math.min(corner,Math.min(face.width(),face.height())/2);clip.reset();clip.addRoundRect(face,radius,radius,Path.Direction.CW);
            int[] colors=tone==DARK?new int[]{0xc8484b50,0xd0202327,0xcc17191d}:tone==ACTIVE?new int[]{0xf044474d,0xf02e3137,0xf023262b}:tone==PEARL?new int[]{0xeeffffff,0xe7edf0f2,0xefcbd0d5}:new int[]{0xb8ffffff,0x92f9f9f8,0xb4ebedec};
            if(tone==DAYBOOK)colors=new int[]{warmSurface,warmSurface,blend(warmSurface,warmSoft,.45f)};
            else if(tone==DAYBOOK_ACTION)colors=new int[]{blend(warmAction,warmSurface,.10f),warmAction,blend(warmAction,Color.BLACK,.08f)};
            fill=new LinearGradient(face.left,face.top,face.right,face.bottom,colors,new float[]{0,.48f,1},Shader.TileMode.CLAMP);
            rim=new LinearGradient(face.left,face.top,face.right,face.bottom,new int[]{tone==LIGHT || tone==PEARL?0xdfffffff:0x64ffffff,0x18ffffff,tone==LIGHT?0x52ffffff:0x2481858a},new float[]{0,.55f,1},Shader.TileMode.CLAMP);
            if(tone==DAYBOOK || tone==DAYBOOK_ACTION)rim=new LinearGradient(face.left,face.top,face.right,face.bottom,new int[]{blend(warmSurface,Color.WHITE,.35f),warmLine},null,Shader.TileMode.CLAMP);
            sheen=new RadialGradient(face.left+face.width()*.15f,face.top-face.height()*.12f,Math.max(1,Math.max(face.width(),face.height())*.85f),new int[]{tone==LIGHT?0x44ffffff:0x16ffffff,0x00ffffff},null,Shader.TileMode.CLAMP);
        }
        @Override public void draw(Canvas canvas){
            if(fill==null || face.isEmpty())return;paint.setAlpha(alpha);paint.setStyle(Paint.Style.FILL);paint.setShader(fill);canvas.drawRoundRect(face,radius,radius,paint);
            canvas.save();canvas.clipPath(clip);paint.setShader(sheen);canvas.drawRect(face,paint);canvas.restore();
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setShader(rim);canvas.drawRoundRect(face,radius,radius,paint);paint.setShader(null);
        }
        @Override public void getOutline(Outline outline){outline.setRoundRect(getBounds(),radius);outline.setAlpha(.18f);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        @Override public void setAlpha(int alpha){this.alpha=alpha;invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        private int blend(int first,int second,float fraction){return Color.rgb(Math.round(Color.red(first)*(1-fraction)+Color.red(second)*fraction),Math.round(Color.green(first)*(1-fraction)+Color.green(second)*fraction),Math.round(Color.blue(first)*(1-fraction)+Color.blue(second)*fraction));}
    }
    static final class Backdrop extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader base,light,shade,mist;
        private int alpha=255;
        @Override protected void onBoundsChange(Rect bounds){float w=Math.max(1,bounds.width()),h=Math.max(1,bounds.height());
            base=new LinearGradient(0,0,w,h,new int[]{0xffeff0ee,0xffe6e8e7,0xffedeef0},new float[]{0,.55f,1},Shader.TileMode.CLAMP);
            light=new RadialGradient(w*.95f,h*.12f,w*.95f,new int[]{0x77ffffff,0x00ffffff},null,Shader.TileMode.CLAMP);
            shade=new RadialGradient(w*.02f,h*.48f,w*1.12f,new int[]{0x22717a80,0x00717a80},null,Shader.TileMode.CLAMP);
            mist=new RadialGradient(w*.88f,h*.90f,w,new int[]{0x55ffffff,0x00ffffff},null,Shader.TileMode.CLAMP);
        }
        @Override public void draw(Canvas canvas){if(base==null)return;paint.setAlpha(alpha);for(Shader shader:new Shader[]{base,light,shade,mist}){paint.setShader(shader);canvas.drawRect(getBounds(),paint);}paint.setShader(null);}
        @Override public int getOpacity(){return PixelFormat.OPAQUE;}
        @Override public void setAlpha(int alpha){this.alpha=alpha;invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    }
    private Glass(){}
}
