package com.example.trafficsigncnn;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.github.mikephil.charting.animation.Easing;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.PercentFormatter;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Statistics screen — aggregated scan data from Firestore.
 *
 * Sections:
 *  1. Summary cards (total, today, 7-day, avg confidence)
 *  2. Top scanned traffic signs (ranked list, max 7)
 *  3. PieChart — label distribution
 *
 * Data source: FirestoreRepository.loadStatistics() (client-side aggregation).
 *
 * States: loading overlay → data / empty state / error toast
 */
public class StatisticsActivity extends AppCompatActivity {

    private static final String TAG = "StatisticsActivity";
    private static final int    MAX_PIE_SLICES = 7;

    // Palette — sapphire shades for dark OLED theme
    private static final int[] CHART_COLORS = {
            0xFF3B82F6, // sapphire primary
            0xFF22C55E, // green
            0xFFF59E0B, // amber
            0xFFEC4899, // pink
            0xFF8B5CF6, // violet (only in chart — not banned in chart context)
            0xFF06B6D4, // cyan
            0xFFEF4444  // red
    };

    private FirestoreRepository repo;

    // Views
    private TextView      tvStatTotal;
    private TextView      tvStatToday;
    private TextView      tvStatWeek;
    private TextView      tvStatAvgConf;
    private LinearLayout  llTopLabels;
    private PieChart      pieChart;
    private View          loadingView;
    private View          emptyStateView;
    private View          statsScrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_statistics);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(android.R.id.content),
                (v, insets) -> {
                    int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
                    v.setPadding(0, top, 0, 0);
                    return insets;
                });

        repo = FirestoreRepository.getInstance();

        bindViews();
        setupChart();

        FloatingActionButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    // ─── View binding ─────────────────────────────────────────────────────

    private void bindViews() {
        tvStatTotal    = findViewById(R.id.tvStatTotal);
        tvStatToday    = findViewById(R.id.tvStatToday);
        tvStatWeek     = findViewById(R.id.tvStatWeek);
        tvStatAvgConf  = findViewById(R.id.tvStatAvgConf);
        llTopLabels    = findViewById(R.id.llTopLabels);
        pieChart       = findViewById(R.id.pieChart);
        loadingView    = findViewById(R.id.loadingView);
        emptyStateView = findViewById(R.id.emptyStateView);
        statsScrollView = findViewById(R.id.statsScrollView);
    }

    // ─── PieChart base setup ──────────────────────────────────────────────

    private void setupChart() {
        pieChart.setUsePercentValues(true);
        pieChart.getDescription().setEnabled(false);
        pieChart.setDrawHoleEnabled(true);
        pieChart.setHoleColor(0xFF0D1F3C);          // dashboard_surface
        pieChart.setTransparentCircleColor(0xFF0D1F3C);
        pieChart.setTransparentCircleAlpha(110);
        pieChart.setHoleRadius(52f);
        pieChart.setTransparentCircleRadius(57f);
        pieChart.setDrawCenterText(true);
        pieChart.setCenterText("Biển báo");
        pieChart.setCenterTextColor(0xFFF0F4FF);    // dashboard_text_primary
        pieChart.setCenterTextSize(13f);
        pieChart.setRotationEnabled(false);
        pieChart.setHighlightPerTapEnabled(true);
        pieChart.setDrawEntryLabels(false);

        // Legend
        com.github.mikephil.charting.components.Legend legend = pieChart.getLegend();
        legend.setEnabled(true);
        legend.setTextColor(0xFF7B93B8);            // dashboard_text_secondary
        legend.setTextSize(11f);
        legend.setWordWrapEnabled(true);
        legend.setMaxSizePercent(0.95f);
    }

    // ─── Data loading ─────────────────────────────────────────────────────

    private void loadData() {
        showLoading(true);
        repo.loadStatistics(new FirestoreRepository.StatisticsCallback() {
            @Override
            public void onSuccess(UserStatistics stats) {
                runOnUiThread(() -> {
                    showLoading(false);
                    if (stats.getTotalScans() == 0) {
                        showEmptyState(true);
                        return;
                    }
                    showEmptyState(false);
                    bindSummaryCards(stats);
                    bindTopLabels(stats.getLabelCounts());
                    bindPieChart(stats.getLabelCounts());
                });
            }

            @Override
            public void onFailure(Exception e) {
                Log.e(TAG, "loadStatistics failed", e);
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(StatisticsActivity.this,
                            "Không thể tải dữ liệu: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    showEmptyState(true);
                });
            }
        });
    }

    // ─── Bind summary cards ───────────────────────────────────────────────

    private void bindSummaryCards(UserStatistics stats) {
        tvStatTotal.setText(String.valueOf(stats.getTotalScans()));
        tvStatToday.setText(String.valueOf(stats.getScansToday()));
        tvStatWeek.setText(String.valueOf(stats.getScansThisWeek()));

        float pct = stats.getAverageConfidence() * 100f;
        tvStatAvgConf.setText(String.format(Locale.getDefault(), "%.1f%%", pct));
    }

    // ─── Bind top labels list ─────────────────────────────────────────────

    private void bindTopLabels(Map<String, Integer> labelCounts) {
        llTopLabels.removeAllViews();
        if (labelCounts == null || labelCounts.isEmpty()) return;

        int rank = 1;
        for (Map.Entry<String, Integer> entry : labelCounts.entrySet()) {
            if (rank > 10) break;

            View row = LayoutInflater.from(this)
                    .inflate(R.layout.item_top_label, llTopLabels, false);

            TextView tvRank  = row.findViewById(R.id.tvRank);
            TextView tvLabel = row.findViewById(R.id.tvLabelName);
            TextView tvCount = row.findViewById(R.id.tvLabelCount);

            tvRank.setText(String.valueOf(rank));
            tvLabel.setText(entry.getKey());
            tvCount.setText(entry.getValue() + " lần");

            // Accent color for top 3
            int color = rank == 1 ? 0xFFF59E0B
                      : rank == 2 ? 0xFF7B93B8
                      : rank == 3 ? 0xFFCD7F32
                      : 0xFF3D5A80;
            tvRank.setTextColor(color);

            llTopLabels.addView(row);
            rank++;
        }
    }

    // ─── Bind PieChart ────────────────────────────────────────────────────

    private void bindPieChart(Map<String, Integer> labelCounts) {
        if (labelCounts == null || labelCounts.isEmpty()) {
            pieChart.setVisibility(View.GONE);
            return;
        }
        pieChart.setVisibility(View.VISIBLE);

        List<PieEntry> entries  = new ArrayList<>();
        List<Integer>  colors   = new ArrayList<>();
        int            idx      = 0;
        int            others   = 0;

        for (Map.Entry<String, Integer> entry : labelCounts.entrySet()) {
            if (idx < MAX_PIE_SLICES) {
                entries.add(new PieEntry(entry.getValue(), shorten(entry.getKey())));
                colors.add(CHART_COLORS[idx % CHART_COLORS.length]);
            } else {
                others += entry.getValue();
            }
            idx++;
        }

        if (others > 0) {
            entries.add(new PieEntry(others, "Khác"));
            colors.add(0xFF4A6080);
        }

        PieDataSet dataSet = new PieDataSet(entries, "");
        dataSet.setColors(colors);
        dataSet.setSliceSpace(2f);
        dataSet.setSelectionShift(6f);
        dataSet.setValueTextSize(11f);
        dataSet.setValueTextColor(0xFFFFFFFF);
        dataSet.setValueFormatter(new PercentFormatter(pieChart));

        PieData data = new PieData(dataSet);
        pieChart.setData(data);
        pieChart.animateY(900, Easing.EaseInOutQuad);
        pieChart.invalidate();
    }

    /** Shorten long label names for PieChart legend readability. */
    private String shorten(String label) {
        if (label.length() <= 20) return label;
        return label.substring(0, 17) + "…";
    }

    // ─── UI helpers ───────────────────────────────────────────────────────

    private void showLoading(boolean show) {
        loadingView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showEmptyState(boolean show) {
        emptyStateView.setVisibility(show ? View.VISIBLE : View.GONE);
        statsScrollView.setVisibility(show ? View.GONE : View.VISIBLE);
    }
}
