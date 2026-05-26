package com.example.trafficsigncnn;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.bumptech.glide.Glide;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.Timestamp;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Shows full detail for a single scan history item.
 *
 * Receives a ScanHistory via Intent extras:
 *   EXTRA_LABEL, EXTRA_CONFIDENCE, EXTRA_SOURCE, EXTRA_IMAGE_URL,
 *   EXTRA_TIMESTAMP_SECONDS, EXTRA_TIMESTAMP_NANOS
 *
 * Displays:
 *  - Full-size image (Glide from imageUrl, or placeholder)
 *  - Label, confidence %, source, formatted timestamp
 */
public class HistoryDetailActivity extends AppCompatActivity {

    public static final String EXTRA_LABEL             = "detail_label";
    public static final String EXTRA_CONFIDENCE        = "detail_confidence";
    public static final String EXTRA_SOURCE            = "detail_source";
    public static final String EXTRA_IMAGE_URL         = "detail_image_url";
    public static final String EXTRA_TIMESTAMP_SECONDS = "detail_ts_seconds";
    public static final String EXTRA_TIMESTAMP_NANOS   = "detail_ts_nanos";

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());

    /** Build a launch intent from a ScanHistory model. */
    public static Intent buildIntent(android.content.Context ctx, ScanHistory item) {
        Intent intent = new Intent(ctx, HistoryDetailActivity.class);
        intent.putExtra(EXTRA_LABEL,      item.getLabel() != null ? item.getLabel() : "");
        intent.putExtra(EXTRA_CONFIDENCE, item.getConfidence());
        intent.putExtra(EXTRA_SOURCE,     item.getSource() != null ? item.getSource() : "");
        intent.putExtra(EXTRA_IMAGE_URL,  item.getImageUrl() != null ? item.getImageUrl() : "");
        Timestamp ts = item.getTimestamp();
        if (ts != null) {
            intent.putExtra(EXTRA_TIMESTAMP_SECONDS, ts.getSeconds());
            intent.putExtra(EXTRA_TIMESTAMP_NANOS,   ts.getNanoseconds());
        }
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_history_detail);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(android.R.id.content),
                (v, insets) -> {
                    int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
                    v.setPadding(0, top, 0, 0);
                    return insets;
                });

        FloatingActionButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        Intent intent = getIntent();
        bindViews(intent);
    }

    private void bindViews(Intent intent) {
        ImageView ivImage          = findViewById(R.id.ivDetailImage);
        TextView  tvLabel          = findViewById(R.id.tvDetailLabel);
        TextView  tvConfidence     = findViewById(R.id.tvDetailConfidence);
        TextView  tvSource         = findViewById(R.id.tvDetailSource);
        TextView  tvTimestamp      = findViewById(R.id.tvDetailTimestamp);

        // Label
        String label = intent.getStringExtra(EXTRA_LABEL);
        tvLabel.setText(label != null && !label.isEmpty() ? label : "—");

        // Confidence
        float conf = intent.getFloatExtra(EXTRA_CONFIDENCE, 0f);
        int pct = Math.round(conf * 100f);
        tvConfidence.setText(pct + "%");
        int color = pct >= 80 ? 0xFF22C55E : pct >= 65 ? 0xFFF59E0B : 0xFFEF4444;
        tvConfidence.setTextColor(color);

        // Source
        String source = intent.getStringExtra(EXTRA_SOURCE);
        tvSource.setText(source != null && !source.isEmpty() ? source : "—");

        // Timestamp
        long seconds = intent.getLongExtra(EXTRA_TIMESTAMP_SECONDS, -1L);
        if (seconds >= 0) {
            int nanos = intent.getIntExtra(EXTRA_TIMESTAMP_NANOS, 0);
            Timestamp ts = new Timestamp(seconds, nanos);
            tvTimestamp.setText(DATE_FMT.format(ts.toDate()));
        } else {
            tvTimestamp.setText("—");
        }

        // Image
        String imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL);
        if (imageUrl != null && !imageUrl.isEmpty()) {
            Glide.with(this)
                    .load(imageUrl)
                    .centerCrop()
                    .into(ivImage);
        } else {
            ivImage.setVisibility(View.GONE);
        }
    }
}
