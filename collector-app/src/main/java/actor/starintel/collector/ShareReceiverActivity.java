package actor.starintel.collector;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

public final class ShareReceiverActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int count = ObservationInbox.capture(this, getIntent());
        Toast.makeText(this, "Queued for StarIntel Collector · " + count + " local", Toast.LENGTH_SHORT).show();
        finish();
    }
}
