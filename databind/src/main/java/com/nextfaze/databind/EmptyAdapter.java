package com.nextfaze.databind;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListAdapter;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;

@Accessors(prefix = "m")
public abstract class EmptyAdapter extends ListAdapterWrapper {

    @NonNull
    private final Data<?> mData;

    @NonNull
    private final LoadingObserver mLoadingObserver = new LoadingObserver() {
        @Override
        public void onLoadingChange() {
            notifyDataSetChanged();
        }
    };

    @Getter
    @Setter
    private boolean mEmptyItemEnabled;

    @NonNull
    public static EmptyAdapter create(@NonNull Data<?> data, @NonNull ListAdapter adapter, final int emptyItemResource) {
        return new EmptyAdapter(data, adapter) {
            @NonNull
            @Override
            protected View newEmptyView(@NonNull LayoutInflater layoutInflater,
                                        int position,
                                        @NonNull ViewGroup parent) {
                return layoutInflater.inflate(emptyItemResource, parent, false);
            }
        };
    }

    @NonNull
    public static EmptyAdapter create(@NonNull Data<?> data, @NonNull ListAdapter adapter, @NonNull final View emptyView) {
        return new EmptyAdapter(data, adapter) {
            @NonNull
            @Override
            protected View newEmptyView(@NonNull LayoutInflater layoutInflater,
                                        int position,
                                        @NonNull ViewGroup parent) {
                return emptyView;
            }
        };
    }

    public EmptyAdapter(@NonNull Data<?> data, @NonNull ListAdapter adapter) {
        super(adapter);
        mData = data;
        mData.registerLoadingObserver(mLoadingObserver);
    }

    @Override
    public void dispose() {
        super.dispose();
        mData.unregisterLoadingObserver(mLoadingObserver);
    }

    @Override
    public int getCount() {
        if (shouldShowEmptyItem()) {
            return mAdapter.getCount() + 1;
        }
        return mAdapter.getCount();
    }

    @Override
    public int getViewTypeCount() {
        // Fixed amount of view types: whatever the underlying adapter wants, plus our empty item.
        return super.getViewTypeCount() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        if (shouldShowEmptyItem() && isEmptyItem(position)) {
            return getEmptyViewType();
        }
        return super.getItemViewType(position);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (getItemViewType(position) == getEmptyViewType()) {
            if (convertView == null) {
                convertView = newEmptyView(getLayoutInflater(parent), position, parent);
            }
            return convertView;
        }
        return super.getView(position, convertView, parent);
    }

    @Override
    public boolean isEnabled(int position) {
        if (shouldShowEmptyItem() && isEmptyItem(position)) {
            return mEmptyItemEnabled;
        }
        return super.isEnabled(position);
    }

    @NonNull
    protected abstract View newEmptyView(@NonNull LayoutInflater layoutInflater, int position, @NonNull ViewGroup parent);

    private boolean shouldShowEmptyItem() {
        return !mData.isLoading() && mData.isEmpty();
    }

    private boolean isEmptyItem(int position) {
        return position == getCount() - 1;
    }

    private int getEmptyViewType() {
        return super.getViewTypeCount();
    }

    @NonNull
    private static LayoutInflater getLayoutInflater(@NonNull View v) {
        return LayoutInflater.from(v.getContext());
    }
}