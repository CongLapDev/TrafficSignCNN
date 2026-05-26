package com.example.trafficsigncnn;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;

/**
 * History screen — shows all past scan results for the current user.
 *
 * Features:
 *  - Force-reloads Firestore on every open (onResume)
 *  - Pull-to-refresh via SwipeRefreshLayout
 *  - Real-time search filter by label (no extra network call)
 *  - Optimistic delete (immediate UI feedback, rollback on failure)
 *  - Delete all with confirmation dialog
 *  - Empty state + loading spinner
 */
public class HistoryActivity extends AppCompatActivity {

    private FirestoreRepository    repo;
    private ScanHistoryAdapter     adapter;

    private RecyclerView           recyclerHistory;
    private SwipeRefreshLayout     swipeRefresh;
    private View                   emptyStateView;
    private View                   loadingView;
    private TextView               tvScanCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_history);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(android.R.id.content),
                (v, insets) -> {
                    int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
                    v.setPadding(0, top, 0, 0);
                    return insets;
                });

        repo = FirestoreRepository.getInstance();

        bindViews();
        setupRecyclerView();
        setupSearch();
        setupSwipeRefresh();
        setupDeleteAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Force-reload every time the screen becomes visible.
        // This ensures items saved in LiveScan/Capture/Gallery appear immediately.
        loadHistory(false);
    }

    // ─── View binding ────────────────────────────────────────────────────

    private void bindViews() {
        recyclerHistory = findViewById(R.id.recyclerHistory);
        swipeRefresh    = findViewById(R.id.swipeRefresh);
        emptyStateView  = findViewById(R.id.emptyStateView);
        loadingView     = findViewById(R.id.loadingView);
        tvScanCount     = findViewById(R.id.tvScanCount);

        FloatingActionButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        // Style the swipe-refresh indicator to match blue accent
        swipeRefresh.setColorSchemeColors(
                getResources().getColor(R.color.dashboard_primary, getTheme()));
        swipeRefresh.setProgressBackgroundColorSchemeColor(
                getResources().getColor(R.color.dashboard_surface, getTheme()));
    }

    // ─── RecyclerView ────────────────────────────────────────────────────

    private void setupRecyclerView() {
        adapter = new ScanHistoryAdapter(new ArrayList<>(), this::onDeleteItem);
        recyclerHistory.setLayoutManager(new LinearLayoutManager(this));
        recyclerHistory.setAdapter(adapter);
        recyclerHistory.setHasFixedSize(false);
    }

    // ─── Search ──────────────────────────────────────────────────────────

    private void setupSearch() {
        SearchView searchView = findViewById(R.id.searchView);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                adapter.filter(query);
                updateEmptyState();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                adapter.filter(newText);
                updateEmptyState();
                return true;
            }
        });
    }

    // ─── Swipe-to-refresh ────────────────────────────────────────────────

    private void setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener(() -> loadHistory(true));
    }

    // ─── Delete All ──────────────────────────────────────────────────────

    private void setupDeleteAll() {
        MaterialButton btnDeleteAll = findViewById(R.id.btnDeleteAll);
        btnDeleteAll.setOnClickListener(v -> showDeleteAllDialog());
    }

    private void showDeleteAllDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Xóa tất cả lịch sử")
                .setMessage("Bạn có chắc chắn muốn xóa toàn bộ lịch sử quét?\nHành động này không thể hoàn tác.")
                .setPositiveButton("Xóa tất cả", (dialog, which) -> performDeleteAll())
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void performDeleteAll() {
        showLoading(true);
        repo.deleteAllHistory(new FirestoreRepository.SimpleCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    adapter.setItems(new ArrayList<>());
                    tvScanCount.setText("0 lượt");
                    updateEmptyState();
                    showLoading(false);
                    Toast.makeText(HistoryActivity.this,
                            "Đã xóa toàn bộ lịch sử", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onFailure(Exception e) {
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(HistoryActivity.this,
                            "Xóa thất bại: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ─── Delete single ────────────────────────────────────────────────────

    private void onDeleteItem(ScanHistory item, int position) {
        if (item.getId() == null) return;

        // Optimistic: remove from UI immediately
        adapter.removeItem(item.getId());
        updateEmptyState();
        updateCountFromAdapter();

        repo.deleteScan(item.getId(), new FirestoreRepository.SimpleCallback() {
            @Override
            public void onSuccess() {
                // Already removed from UI — nothing extra needed
            }

            @Override
            public void onFailure(Exception e) {
                // Rollback: reload list
                runOnUiThread(() -> {
                    Toast.makeText(HistoryActivity.this,
                            "Xóa thất bại, đang tải lại...", Toast.LENGTH_SHORT).show();
                    loadHistory(false);
                });
            }
        });
    }

    // ─── Load history ─────────────────────────────────────────────────────

    /**
     * Load scan history from Firestore.
     *
     * @param fromSwipe true = triggered by pull-to-refresh gesture
     *                  (uses SwipeRefreshLayout spinner instead of full overlay)
     */
    private void loadHistory(boolean fromSwipe) {
        if (!fromSwipe) {
            showLoading(true);
        }

        repo.getScanHistory(new FirestoreRepository.HistoryCallback() {
            @Override
            public void onSuccess(java.util.List<ScanHistory> items) {
                runOnUiThread(() -> {
                    adapter.setItems(items);
                    updateEmptyState();
                    updateCountFromAdapter();
                    showLoading(false);
                    if (fromSwipe) swipeRefresh.setRefreshing(false);
                });
            }

            @Override
            public void onFailure(Exception e) {
                runOnUiThread(() -> {
                    showLoading(false);
                    if (fromSwipe) swipeRefresh.setRefreshing(false);
                    Toast.makeText(HistoryActivity.this,
                            "Không tải được lịch sử: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    updateEmptyState();
                });
            }
        });
    }

    // ─── UI helpers ───────────────────────────────────────────────────────

    private void showLoading(boolean show) {
        loadingView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void updateEmptyState() {
        boolean empty = adapter.isEmpty();
        emptyStateView.setVisibility(empty ? View.VISIBLE : View.GONE);
        swipeRefresh.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void updateCountFromAdapter() {
        tvScanCount.setText(adapter.getItemCount() + " lượt");
    }
}
