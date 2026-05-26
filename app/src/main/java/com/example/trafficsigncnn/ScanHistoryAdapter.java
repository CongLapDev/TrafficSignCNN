package com.example.trafficsigncnn;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.google.firebase.Timestamp;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for scan history items.
 *
 * Features:
 *  - Full dataset + filtered dataset (for search)
 *  - Label search filter
 *  - Per-item delete callback
 *  - Source icon mapping (live → camera, capture → camera, gallery → gallery)
 *  - Confidence color: ≥80% green | ≥65% amber | <65% red
 */
public class ScanHistoryAdapter extends RecyclerView.Adapter<ScanHistoryAdapter.ViewHolder> {

    public interface OnDeleteClickListener {
        void onDelete(ScanHistory item, int position);
    }

    public interface OnItemClickListener {
        void onItemClick(ScanHistory item);
    }

    private final List<ScanHistory> allItems;      // master list
    private final List<ScanHistory> displayItems;  // filtered list shown in RecyclerView
    private final OnDeleteClickListener deleteListener;
    private OnItemClickListener itemClickListener;

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());

    public ScanHistoryAdapter(@NonNull List<ScanHistory> items,
                              @NonNull OnDeleteClickListener deleteListener) {
        this.allItems      = new ArrayList<>(items);
        this.displayItems  = new ArrayList<>(items);
        this.deleteListener = deleteListener;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.itemClickListener = listener;
    }

    // ─── Filter ──────────────────────────────────────────────────────────

    public void filter(String query) {
        displayItems.clear();
        if (query == null || query.trim().isEmpty()) {
            displayItems.addAll(allItems);
        } else {
            String lower = query.trim().toLowerCase(Locale.getDefault());
            for (ScanHistory item : allItems) {
                if (item.getLabel() != null &&
                        item.getLabel().toLowerCase(Locale.getDefault()).contains(lower)) {
                    displayItems.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    /** Replace all data (used after loading from Firestore). */
    public void setItems(@NonNull List<ScanHistory> items) {
        allItems.clear();
        allItems.addAll(items);
        displayItems.clear();
        displayItems.addAll(items);
        notifyDataSetChanged();
    }

    /** Remove a single item from both lists by scanId. */
    public void removeItem(String scanId) {
        allItems.removeIf(i -> scanId.equals(i.getId()));
        int pos = -1;
        for (int i = 0; i < displayItems.size(); i++) {
            if (scanId.equals(displayItems.get(i).getId())) {
                pos = i;
                break;
            }
        }
        if (pos >= 0) {
            displayItems.remove(pos);
            notifyItemRemoved(pos);
        }
    }

    public boolean isEmpty() {
        return displayItems.isEmpty();
    }

    // ─── RecyclerView boilerplate ─────────────────────────────────────────

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_scan_history, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ScanHistory item = displayItems.get(position);
        holder.bind(item);

        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_ID) {
                deleteListener.onDelete(displayItems.get(pos), pos);
            }
        });

        holder.itemView.setOnClickListener(v -> {
            if (itemClickListener != null) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_ID) {
                    itemClickListener.onItemClick(displayItems.get(pos));
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return displayItems.size();
    }

    // ─── ViewHolder ──────────────────────────────────────────────────────

    static class ViewHolder extends RecyclerView.ViewHolder {

        final ImageView   ivThumbnail;
        final View        iconCircle;
        final ImageView   ivSourceIcon;
        final TextView    tvLabel;
        final TextView    tvConfidence;
        final TextView    tvSource;
        final TextView    tvTimestamp;
        final ImageButton btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivThumbnail  = itemView.findViewById(R.id.ivThumbnail);
            iconCircle   = itemView.findViewById(R.id.iconCircle);
            ivSourceIcon = itemView.findViewById(R.id.ivSourceIcon);
            tvLabel      = itemView.findViewById(R.id.tvLabel);
            tvConfidence = itemView.findViewById(R.id.tvConfidence);
            tvSource     = itemView.findViewById(R.id.tvSource);
            tvTimestamp  = itemView.findViewById(R.id.tvTimestamp);
            btnDelete    = itemView.findViewById(R.id.btnDeleteItem);
        }

        void bind(@NonNull ScanHistory item) {
            // Label
            tvLabel.setText(item.getLabel() != null ? item.getLabel() : "—");

            // Confidence chip — color-coded
            int pct = Math.round(item.getConfidence() * 100f);
            tvConfidence.setText(pct + "%");
            int confColor;
            if (pct >= 80) {
                confColor = 0xFF22C55E; // green
            } else if (pct >= 65) {
                confColor = 0xFFF59E0B; // amber
            } else {
                confColor = 0xFFEF4444; // red
            }
            tvConfidence.setTextColor(confColor);

            // Source chip
            String source = item.getSource() != null ? item.getSource() : "—";
            tvSource.setText(source);

            // Thumbnail or source icon
            String imageUrl = item.getImageUrl();
            if (imageUrl != null && !imageUrl.isEmpty()) {
                ivThumbnail.setVisibility(View.VISIBLE);
                iconCircle.setVisibility(View.GONE);
                Glide.with(ivThumbnail.getContext())
                        .load(imageUrl)
                        .centerCrop()
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(ivThumbnail);
            } else {
                ivThumbnail.setVisibility(View.GONE);
                iconCircle.setVisibility(View.VISIBLE);
                switch (source) {
                    case "gallery":
                        ivSourceIcon.setImageResource(R.drawable.ic_gallery);
                        break;
                    case "live":
                    case "capture":
                    default:
                        ivSourceIcon.setImageResource(R.drawable.ic_camera);
                        break;
                }
            }

            // Timestamp
            Timestamp ts = item.getTimestamp();
            if (ts != null) {
                Date date = ts.toDate();
                tvTimestamp.setText(DATE_FMT.format(date));
            } else {
                tvTimestamp.setText("—");
            }
        }
    }
}
