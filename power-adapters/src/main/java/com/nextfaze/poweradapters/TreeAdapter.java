package com.nextfaze.poweradapters;

import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.CallSuper;
import android.support.annotation.Nullable;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.WeakHashMap;

import static java.lang.String.format;

public abstract class TreeAdapter extends AbstractPowerAdapter {

    @NonNull
    private final DataObserver mRootDataObserver = new SimpleDataObserver() {
        @Override
        public void onChanged() {
            invalidateGroups();
            notifyDataSetChanged();
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            invalidateGroups(); // TODO: Not needed?
            notifyItemRangeChanged(rootToOuter(positionStart), itemCount);
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            invalidateGroups();
            notifyItemRangeInserted(rootToOuter(positionStart), itemCount);
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            invalidateGroups();
            notifyItemRangeRemoved(rootToOuter(positionStart), itemCount);
        }

        @Override
        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
            // TODO: Item count should include children of all expanded root items.
            invalidateGroups();
            notifyItemRangeMoved(rootToOuter(fromPosition), rootToOuter(toPosition), itemCount);
        }
    };

    @NonNull
    private TreeState mState = new TreeState();

    @NonNull
    private final Transform mRootTransform = new Transform() {
        @Override
        public int transform(int position) {
            return outerToRoot(position);
        }
    };

    @NonNull
    private final PowerAdapter mRootAdapter;

    /** Reused to wrap an adapter and automatically offset all position calls. */
    @NonNull
    private final OffsetAdapter mOffsetAdapter = new OffsetAdapter();

    @NonNull
    private final SparseArray<Entry> mEntries = new SparseArray<>();

    @NonNull
    private final GroupPool mGroupPool = new GroupPool();

    // TODO: Remove mappings from the following when an Entry is closed.
    @NonNull
    private final Map<ViewType, PowerAdapter> mAdaptersByViewType = new HashMap<>();

    /** Contains the mapping of outer positions to groups. */
    @NonNull
    private final SparseArray<Group> mGroups = new SparseArray<>();

    private boolean mDirty = true;

    public TreeAdapter(@NonNull PowerAdapter rootAdapter) {
        mRootAdapter = rootAdapter;
    }

    @NonNull
    protected abstract PowerAdapter getChildAdapter(int position);

    /** Returns the parcelable state of the adapter. */
    @NonNull
    public Parcelable saveInstanceState() {
        return mState;
    }

    /**
     * Restores the state of the adapter from a previous state parcelable. Only effective when root adapter {@link
     * PowerAdapter#hasStableIds()}
     */
    public void restoreInstanceState(@Nullable Parcelable parcelable) {
        if (parcelable != null) {
            mState = (TreeState) parcelable;
            applyExpandedState();
        }
    }

    private void applyExpandedState() {
        if (mRootAdapter.hasStableIds() && !mState.isEmpty()) {
            for (int i = 0; i < mRootAdapter.getItemCount(); i++) {
                long itemId = mRootAdapter.getItemId(i);
                if (itemId != NO_ID) {
                    setExpanded(i, mState.isExpanded(itemId));
                }
            }
        }
    }

    public boolean isExpanded(int position) {
        return mEntries.get(position) != null;
    }

    public void setExpanded(int position, boolean expanded) {
        Entry entry = mEntries.get(position);
        long itemId = mRootAdapter.getItemId(position);
        if (expanded) {
            if (entry == null) {
                entry = new Entry(getChildAdapter(position));
                mEntries.put(position, entry);
                mState.setExpanded(itemId, true);
                invalidateGroups();
                notifyItemRangeInserted(rootToOuter(position) + 1, entry.getItemCount());
                entry.updateObserver();
            }
        } else {
            if (entry != null) {
                int preDisposeItemCount = entry.getItemCount();
                entry.dispose();
                mEntries.remove(position);
                mState.setExpanded(itemId, false);
                invalidateGroups();
                notifyItemRangeRemoved(rootToOuter(position) + 1, preDisposeItemCount);
            }
        }
    }

    public boolean toggleExpanded(int position) {
        boolean expanded = isExpanded(position);
        setExpanded(position, !expanded);
        return !expanded;
    }

    public void setAllExpanded(boolean expanded) {
        ArrayList<Runnable> notifications = new ArrayList<>();
        if (!expanded) {
            for (int i = 0; i < mEntries.size(); i++) {
                Entry entry = mEntries.valueAt(i);
                final int position = entry.getPosition();
                final int preDisposeItemCount = entry.getItemCount();
                entry.dispose();
                notifications.add(new Runnable() {
                    @Override
                    public void run() {
                        notifyItemRangeRemoved(rootToOuter(position) + 1, preDisposeItemCount);
                    }
                });
            }
            mEntries.clear();
            mState.clear();
        } else {
            int rootItemCount = mRootAdapter.getItemCount();
            for (int position = 0; position < rootItemCount; position++) {
                Entry entry = mEntries.get(position);
                long itemId = mRootAdapter.getItemId(position);
                if (entry == null) {
                    entry = new Entry(getChildAdapter(position));
                    mEntries.put(position, entry);
                    mState.setExpanded(itemId, true);
                    final int itemCount = entry.getItemCount();
                    final int finalPosition = position;
                    notifications.add(new Runnable() {
                        @Override
                        public void run() {
                            notifyItemRangeInserted(rootToOuter(finalPosition) + 1, itemCount);
                        }
                    });
                }
            }
        }
        updateEntryObservers();
        invalidateGroups();
        for (Runnable runnable : notifications) {
            runnable.run();
        }
    }

    @NonNull
    private OffsetAdapter adapterForPosition(int outerPosition) {
        return groupForPosition(outerPosition).adapter(outerPosition);
    }

    private void invalidateGroups() {
        mDirty = true;
        rebuildGroupsIfNecessary();
        applyExpandedState();
    }

    private void rebuildGroupsIfNecessary() {
        if (mDirty) {
            for (int i = 0; i < mGroups.size(); i++) {
                mGroupPool.release(mGroups.valueAt(i));
            }
            mGroups.clear();
            int outerStart = 0;
            int rootStart = 0;
            int i = 0;
            int rootItemCount = mRootAdapter.getItemCount();
            while (i < rootItemCount) {
                Group group = mGroupPool.obtain();
                group.set(i, outerStart, rootStart);
                mGroups.put(outerStart, group);
                Entry entry = mEntries.get(i);
                if (entry != null) {
                    int entryItemCount = entry.getItemCount();
                    entry.mGroup = group;
                    outerStart += entryItemCount;
                    rootStart += entryItemCount;
                }
                outerStart++;
                i++;
            }
            mDirty = false;
        }
    }

    @NonNull
    private Group groupForPosition(int outerPosition) {
        rebuildGroupsIfNecessary();
        int totalItemCount = getItemCount();
        if (outerPosition >= totalItemCount) {
            throw new ArrayIndexOutOfBoundsException(format("Index: %d, total size: %d", outerPosition, totalItemCount));
        }
        int groupPosition = mGroups.indexOfKey(outerPosition);
        Group group;
        if (groupPosition >= 0) {
            group = mGroups.valueAt(groupPosition);
        } else {
            group = mGroups.valueAt(-groupPosition - 2);
        }
        return group;
    }

    @NonNull
    private PowerAdapter adapterForViewType(@NonNull ViewType viewType) {
        PowerAdapter adapter = mAdaptersByViewType.get(viewType);
        return adapter != null ? adapter : mRootAdapter;
    }

    private int rootToOuter(int rootPosition) {
        rebuildGroupsIfNecessary();
        if (mGroups.size() == 0) {
            return rootPosition;
        }
        return mGroups.valueAt(rootPosition).getOuterStart();
    }

    private int outerToRoot(int outerPosition) {
        return outerPosition - groupForPosition(outerPosition).getRootStart();
    }

    /**
     * By default returns {@code false}, because we don't know all our adapters ahead of time, so can't assume they're
     * stable.
     */
    @Override
    public boolean hasStableIds() {
        return false;
    }

    @Override
    public final int getItemCount() {
        rebuildGroupsIfNecessary();
        if (mGroups.size() == 0) {
            return 0;
        }
        Group group = mGroups.valueAt(mGroups.size() - 1);
        return group.getOuterStart() + group.size();
    }

    @Override
    public final long getItemId(int position) {
        return adapterForPosition(position).getItemId(position);
    }

    @Override
    public final boolean isEnabled(int position) {
        return adapterForPosition(position).isEnabled(position);
    }

    @NonNull
    @Override
    public final ViewType getItemViewType(int position) {
        OffsetAdapter offsetAdapter = adapterForPosition(position);
        ViewType viewType = offsetAdapter.getViewType(position);
        mAdaptersByViewType.put(viewType, offsetAdapter.mAdapter);
        return viewType;
    }

    @NonNull
    @Override
    public final View newView(@NonNull ViewGroup parent, @NonNull ViewType viewType) {
        return adapterForViewType(viewType).newView(parent, viewType);
    }

    @Override
    public final void bindView(@NonNull View view, @NonNull Holder holder) {
        adapterForPosition(holder.getPosition()).bindView(view, holder);
    }

    @CallSuper
    @Override
    protected void onFirstObserverRegistered() {
        super.onFirstObserverRegistered();
        mRootAdapter.registerDataObserver(mRootDataObserver);
        updateEntryObservers();
        invalidateGroups();
    }

    @CallSuper
    @Override
    protected void onLastObserverUnregistered() {
        super.onLastObserverUnregistered();
        mRootAdapter.unregisterDataObserver(mRootDataObserver);
        updateEntryObservers();
    }

    private void updateEntryObservers() {
        for (int i = 0; i < mEntries.size(); i++) {
            mEntries.valueAt(i).updateObserver();
        }
    }

    private final class Entry {

        @NonNull
        private final DataObserver mDataObserver = new SimpleDataObserver() {
            @Override
            public void onChanged() {
                invalidateGroups();
                notifyDataSetChanged();
            }

            @Override
            public void onItemRangeChanged(int positionStart, int itemCount) {
                invalidateGroups();
                notifyItemRangeChanged(entryToOuter(positionStart), itemCount);
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                invalidateGroups();
                notifyItemRangeInserted(entryToOuter(positionStart), itemCount);
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                invalidateGroups();
                notifyItemRangeRemoved(entryToOuter(positionStart), itemCount);
            }

            @Override
            public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
                invalidateGroups();
                notifyItemRangeMoved(entryToOuter(fromPosition), entryToOuter(toPosition), itemCount);
            }
        };

        @NonNull
        private final Transform mTransform = new Transform() {
            @Override
            public int transform(int position) {
                return outerToEntry(position);
            }
        };

        @Nullable
        private DataObserver mRegisteredDataObserver;

        @NonNull
        private final PowerAdapter mAdapter;

        @NonNull
        private Group mGroup;

        Entry(@NonNull PowerAdapter adapter) {
            mAdapter = adapter;
            // Only register child observer if parent has at least 1 observer.
//            if (getObserverCount() > 0) {
//                registerObserversIfNecessary();
//            }
        }

        int entryToOuter(int entryPosition) {
            return mGroup.entryToOuter(entryPosition);
        }

        int outerToEntry(int outerPosition) {
            return groupForPosition(outerPosition).outerToEntry(outerPosition);
        }

        void updateObserver() {
            if (getObserverCount() > 0) {
                registerObserversIfNecessary();
            } else {
                unregisterObserversIfNecessary();
            }
        }

        void registerObserversIfNecessary() {
            if (mRegisteredDataObserver == null) {
                mRegisteredDataObserver = mDataObserver;
                mAdapter.registerDataObserver(mDataObserver);
            }
        }

        void unregisterObserversIfNecessary() {
            DataObserver observer = mRegisteredDataObserver;
            if (observer != null) {
                mRegisteredDataObserver = null;
                mAdapter.unregisterDataObserver(observer);
            }
        }

        int getPosition() {
            return mGroup.getPosition();
        }

        int getItemCount() {
            return mAdapter.getItemCount();
        }

        void dispose() {
            unregisterObserversIfNecessary();
        }
    }

    private static final class OffsetAdapter {

        @NonNull
        private final WeakHashMap<Holder, OffsetHolder> mHolders = new WeakHashMap<>();

        private PowerAdapter mAdapter;
        private Transform mTransform;
        private int mOffset;

        @NonNull
        OffsetAdapter set(@NonNull PowerAdapter adapter, @NonNull Transform transform, int offset) {
            mAdapter = adapter;
            mTransform = transform;
            mOffset = offset;
            return this;
        }

        long getItemId(int position) {
            return mAdapter.getItemId(position - mOffset);
        }

        boolean isEnabled(int position) {
            return mAdapter.isEnabled(position - mOffset);
        }

        void bindView(@NonNull View view, @NonNull Holder holder) {
            OffsetHolder offsetHolder = mHolders.get(holder);
            if (offsetHolder == null) {
                offsetHolder = new OffsetHolder(holder);
                mHolders.put(holder, offsetHolder);
            }
            offsetHolder.transform = mTransform;
            offsetHolder.offset = mOffset;
            mAdapter.bindView(view, offsetHolder);
        }

        @NonNull
        ViewType getViewType(int position) {
            return mAdapter.getItemViewType(position - mOffset);
        }

        private static final class OffsetHolder extends HolderWrapper {

            Transform transform;
            int offset;

            OffsetHolder(@NonNull Holder holder) {
                super(holder);
            }

            @Override
            public int getPosition() {
                return transform.transform(super.getPosition());
            }
        }
    }

    private interface Transform {
        int transform(int position);
    }

    private final class GroupPool {

        @NonNull
        private final ArrayList<Group> mGroups = new ArrayList<>();

        @NonNull
        Group obtain() {
            if (mGroups.isEmpty()) {
                return new Group();
            }
            return mGroups.remove(mGroups.size() - 1);
        }

        void release(@NonNull Group group) {
            mGroups.add(group);
        }
    }

    private final class Group {

        private int mPosition;
        private int mOuterStart;
        private int mRootStart;

        @NonNull
        Group set(int position, int outerStart, int rootStart) {
            mPosition = position;
            mOuterStart = outerStart;
            mRootStart = rootStart;
            return this;
        }

        int entryToOuter(int entryPosition) {
            return getEntryStart() + entryPosition;
        }

        int outerToEntry(int outerPosition) {
            return outerPosition - getEntryStart();
        }

        /** Index of this group within the collection. */
        int getPosition() {
            return mPosition;
        }

        /** Offset of this group in outer adapter coordinate space. */
        int getOuterStart() {
            return mOuterStart;
        }

        /** Offset of this group in the root adapter coordinate space. */
        int getRootStart() {
            return mRootStart;
        }

        int getEntryStart() {
            return mOuterStart + 1;
        }

        int size() {
            int size = 1;
            Entry entry = mEntries.get(getPosition());
            if (entry != null) {
                size += entry.getItemCount();
            }
            return size;
        }

        @NonNull
        OffsetAdapter adapter(int outerPosition) {
            // Does this outer position map to the root adapter?
            if (outerPosition - getOuterStart() == 0) {
                return mOffsetAdapter.set(mRootAdapter, mRootTransform, getRootStart());
            }
            // Outer position maps to the child adapter.
            Entry entry = mEntries.get(getPosition());
            return mOffsetAdapter.set(entry.mAdapter, entry.mTransform, getEntryStart());
        }

        @Override
        public String toString() {
            return format("%s (%s)", mPosition, size());
        }
    }

    static final class TreeState implements Parcelable {

        public static final Creator<TreeState> CREATOR = new Creator<TreeState>() {

            @NonNull
            public TreeState createFromParcel(@NonNull Parcel parcel) {
                return new TreeState(parcel);
            }

            @NonNull
            public TreeState[] newArray(int size) {
                return new TreeState[size];
            }
        };

        @NonNull
        private final HashSet<Long> mExpanded;

        TreeState(@NonNull Parcel parcel) {
            //noinspection unchecked
            mExpanded = (HashSet<Long>) parcel.readSerializable();
        }

        TreeState() {
            mExpanded = new HashSet<>();
        }

        void setExpanded(long itemId, boolean expanded) {
            if (itemId != NO_ID) {
                if (expanded) {
                    mExpanded.add(itemId);
                } else {
                    mExpanded.remove(itemId);
                }
            }
        }

        boolean isExpanded(long itemId) {
            //noinspection SimplifiableIfStatement
            if (itemId == NO_ID) {
                return false;
            }
            return mExpanded.contains(itemId);
        }

        void clear() {
            mExpanded.clear();
        }

        boolean isEmpty() {
            return mExpanded.isEmpty();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeSerializable(mExpanded);
        }
    }
}
