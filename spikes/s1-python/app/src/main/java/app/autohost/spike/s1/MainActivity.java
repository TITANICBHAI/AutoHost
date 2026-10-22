package app.autohost.spike.s1;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "AutoHostS1";

    private ExecutorService pythonExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView output = new TextView(this);
        output.setText("Starting Python...");
        output.setTextIsSelectable(true);
        output.setTextSize(18);
        output.setGravity(Gravity.CENTER);
        output.setPadding(32, 32, 32, 32);
        setContentView(output);

        int androidPid = Process.myPid();
        pythonExecutor = Executors.newSingleThreadExecutor();
        pythonExecutor.execute(() -> {
            String report;
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(getApplicationContext()));
                }

                PyObject result = Python.getInstance()
                        .getModule("hello")
                        .callAttr("run");
                String pythonInfo = result.toString();
                boolean sameProcess = pythonInfo.contains("python_pid=" + androidPid);

                report = (sameProcess ? "PASS" : "FAIL")
                        + "\nAndroid PID: " + androidPid
                        + "\n" + pythonInfo;
            } catch (Exception error) {
                Log.e(TAG, "Python smoke check failed", error);
                report = "FAIL: " + error.getClass().getSimpleName()
                        + ": " + String.valueOf(error.getMessage());
            }

            String finalReport = report;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    output.setText(finalReport);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        if (pythonExecutor != null) {
            pythonExecutor.shutdownNow();
        }
        super.onDestroy();
    }
}
