package fr.vinted;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import com.google.zxing.*;

/** CI fixture only. No real Vinted account, network, branding or user data. Not shipped in Versandhilfe. */
public class PreviewActivity extends Activity {
    private static final String CODE = "OF|TESTVINT|0|Paket bis 2 kg|A|Anna Beispiel||Musterweg 1|10115|Berlin|Germany|Ben Muster||Testweg 2|20095|Hamburg|Germany||||20095156002000|123456789012|27112026||0||V2.0";
    @Override public void onCreate(Bundle state) { super.onCreate(state); show(getIntent().getBooleanExtra("private", false)); }
    private void show(boolean sensitive) {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(28, 100, 28, 28); root.setBackgroundColor(Color.WHITE);
        TextView title = new TextView(this); title.setText("Digitaler Versandschein"); title.setTextSize(24); title.setAccessibilityHeading(true); root.addView(title);
        if (sensitive) {
            TextView label = new TextView(this); label.setText("Test-Login · Passwort"); root.addView(label);
            EditText password = new EditText(this); password.setInputType(129); root.addView(password);
            Button next = new Button(this); next.setText("Versandvorschau öffnen"); next.setAllCaps(false); next.setOnClickListener(v -> show(false)); root.addView(next);
        } else {
            TextView due = new TextView(this); due.setText("Versenden bis 27.11.2026"); root.addView(due);
            try {
                com.google.zxing.common.BitMatrix matrix = new MultiFormatWriter().encode(CODE, BarcodeFormat.QR_CODE, 650, 650);
                Bitmap bitmap = Bitmap.createBitmap(650, 650, Bitmap.Config.ARGB_8888);
                int[] colors = new int[650*650]; for (int y=0;y<650;y++) for (int x=0;x<650;x++) colors[y*650+x] = matrix.get(x,y) ? Color.BLACK : Color.WHITE;
                bitmap.setPixels(colors,0,650,0,0,650,650);
                ImageView qr = new ImageView(this); qr.setImageBitmap(bitmap); qr.setContentDescription("Versand-QR-Code"); qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int side = (int)(280 * getResources().getDisplayMetrics().density); root.addView(qr,new LinearLayout.LayoutParams(side,side));
            } catch (Exception e) { throw new IllegalStateException(e); }
            TextView carrier = new TextView(this); carrier.setText("Versandanbieter DHL"); root.addView(carrier);
        }
        setContentView(root);
    }
}
