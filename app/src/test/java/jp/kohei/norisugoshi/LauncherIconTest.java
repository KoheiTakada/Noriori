package jp.kohei.norisugoshi;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LauncherIconTest {
    @Test public void systemListsLoadAnAdaptiveIconAndSmallSizesRemainLegible() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals(R.mipmap.ic_launcher, context.getApplicationInfo().icon);
        assertEquals("のりおり", context.getPackageManager().getApplicationLabel(context.getApplicationInfo()));
        Drawable loaded = context.getDrawable(context.getApplicationInfo().icon);
        assertTrue("System lists must receive the adaptive resource", loaded instanceof AdaptiveIconDrawable);
        AdaptiveIconDrawable icon = (AdaptiveIconDrawable) loaded;
        assertNotNull(icon.getForeground()); assertNotNull(icon.getBackground());
        File directory = new File("build/qa"); assertTrue(directory.exists() || directory.mkdirs());
        assertNotNull(icon.getIconMask());
        renderMasked(icon, 192, new File(directory, "app-icon.png")).recycle();
        Bitmap small = renderMasked(icon, 32, new File(directory, "app-icon-small.png"));
        int bright = 0;
        for (int x = 0; x < 32; x++) for (int y = 0; y < 32; y++) {
            int pixel = small.getPixel(x, y);
            if (Color.alpha(pixel) > 200 && Color.red(pixel) > 230 && Color.green(pixel) > 230) bright++;
        }
        assertTrue("Train and arrows must survive settings-list sizes", bright > 30);
        int[] train=largestWhiteComponent(small);
        assertTrue("The train front must retain its width in the system icon",train[0]>=train[1]*.75);
        assertEquals(0, Color.alpha(small.getPixel(0, 0)));
        small.recycle();
        Drawable mono = icon.getForeground().getConstantState().newDrawable().mutate();
        mono.setTint(0xFF153126);
        AdaptiveIconDrawable themed = new AdaptiveIconDrawable(new ColorDrawable(0xFFD6E8DE), mono);
        renderMasked(themed, 192, new File(directory, "app-icon-themed.png")).recycle();
        Bitmap variants = Bitmap.createBitmap(768, 256, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(variants); canvas.drawColor(DesignTokens.Color.CANVAS);
        drawMask(canvas, icon, 24, false);
        drawMask(canvas, icon, 280, true);
        drawMask(canvas, themed, 536, false);
        save(variants, new File(directory, "app-icon-masks.png")); variants.recycle();
        Bitmap notification = render(context.getDrawable(R.drawable.ic_noriori), 48,
                new File(directory, "notification-icon.png"));
        int visible = 0;
        for (int x = 0; x < 48; x++) for (int y = 0; y < 48; y++) {
            if (Color.alpha(notification.getPixel(x, y)) > 200) visible++;
        }
        assertTrue("Notification mask must contain a visible symbol", visible > 180);
        notification.recycle();
    }
    private int[] largestWhiteComponent(Bitmap image){
        int w=image.getWidth(),h=image.getHeight(),largest=0;int[] bounds={0,0};boolean[] seen=new boolean[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int start=y*w+x,pixel=image.getPixel(x,y);
            if(seen[start]||Color.alpha(pixel)<200||Color.red(pixel)<230||Color.green(pixel)<230)continue;
            java.util.ArrayDeque<Integer> queue=new java.util.ArrayDeque<>();queue.add(start);seen[start]=true;
            int count=0,left=x,right=x,top=y,bottom=y;
            while(!queue.isEmpty()){
                int i=queue.removeFirst(),cx=i%w,cy=i/w;count++;left=Math.min(left,cx);right=Math.max(right,cx);top=Math.min(top,cy);bottom=Math.max(bottom,cy);
                for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){int nx=cx+d[0],ny=cy+d[1];if(nx<0||ny<0||nx>=w||ny>=h)continue;
                    int n=ny*w+nx,c=image.getPixel(nx,ny);if(!seen[n]&&Color.alpha(c)>=200&&Color.red(c)>=230&&Color.green(c)>=230){seen[n]=true;queue.add(n);}}
            }
            if(count>largest){largest=count;bounds=new int[]{right-left+1,bottom-top+1};}
        }return bounds;
    }
    private void drawMask(Canvas canvas, AdaptiveIconDrawable icon, int left, boolean rounded) {
        canvas.save(); canvas.translate(left, 24);
        drawLayers(canvas, icon, 208, rounded);
        canvas.restore();
    }
    private void drawLayers(Canvas canvas, AdaptiveIconDrawable icon, int size, boolean rounded) {
        Path path = new Path();
        if (rounded) path.addRoundRect(new RectF(0, 0, size, size), size * 0.23f, size * 0.23f, Path.Direction.CW);
        else path.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
        canvas.clipPath(path);
        // The system expands the 108dp layers into its 72dp masked viewport.
        icon.getBackground().setBounds(-size / 4, -size / 4, size * 5 / 4, size * 5 / 4);
        icon.getBackground().draw(canvas);
        icon.getForeground().setBounds(-size / 4, -size / 4, size * 5 / 4, size * 5 / 4);
        icon.getForeground().draw(canvas);
    }
    private Bitmap renderMasked(AdaptiveIconDrawable icon, int size, File output) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        drawLayers(new Canvas(bitmap), icon, size, false); save(bitmap, output); return bitmap;
    }
    private Bitmap render(Drawable icon, int size, File output) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, size, size); icon.draw(new Canvas(bitmap)); save(bitmap, output); return bitmap;
    }
    private void save(Bitmap bitmap, File output) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(output)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
    }
}
