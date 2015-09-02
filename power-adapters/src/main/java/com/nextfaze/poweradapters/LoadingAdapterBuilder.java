package com.nextfaze.poweradapters;

import android.support.annotation.CheckResult;
import android.support.annotation.LayoutRes;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.view.View;
import android.widget.Adapter;
import lombok.NonNull;

import static com.nextfaze.poweradapters.PowerAdapters.concat;
import static com.nextfaze.poweradapters.ViewFactories.viewFactoryForResource;
import static com.nextfaze.poweradapters.ViewFactories.viewFactoryForView;

/** Wraps an existing {@link PowerAdapter} and displays a loading indicator while loading. */
public final class LoadingAdapterBuilder {

    @Nullable
    private Item mItem;

    @NonNull
    private EmptyPolicy mEmptyPolicy = EmptyPolicy.SHOW_ALWAYS;

    private boolean mEnabled;

    @NonNull
    public LoadingAdapterBuilder view(@NonNull View view) {
        return view(viewFactoryForView(view));
    }

    @NonNull
    public LoadingAdapterBuilder resource(@LayoutRes int resource) {
        return view(viewFactoryForResource(resource));
    }

    @NonNull
    public LoadingAdapterBuilder view(@NonNull ViewFactory viewFactory) {
        mItem = new Item(viewFactory, false);
        return this;
    }

    @NonNull
    public LoadingAdapterBuilder enabled(boolean loadingItemEnabled) {
        mEnabled = loadingItemEnabled;
        return this;
    }

    /** If {@code true}, loading item is only shown while {@link Adapter#isEmpty()} is {@code true}. */
    @NonNull
    public LoadingAdapterBuilder emptyPolicy(@NonNull EmptyPolicy emptyPolicy) {
        mEmptyPolicy = emptyPolicy;
        return this;
    }

    @CheckResult
    @NonNull
    public PowerAdapter build(@NonNull PowerAdapter adapter, @NonNull Delegate delegate) {
        if (mItem == null) {
            throw new IllegalStateException("No loading item specified");
        }
        return concat(adapter, new LoadingAdapter(delegate, mItem.withEnabled(mEnabled), mEmptyPolicy));
    }

    /** Determines when the loading item is shown while empty. Item is never shown if not loading. */
    public enum EmptyPolicy {
        /** Show the loading item ONLY while delegate is empty. */
        SHOW_ONLY_IF_EMPTY {
            @Override
            boolean shouldShow(@NonNull Delegate delegate) {
                return delegate.isEmpty();
            }
        },
        /** Show the loading item ONLY while delegate is non-empty. */
        SHOW_ONLY_IF_NON_EMPTY {
            @Override
            boolean shouldShow(@NonNull Delegate delegate) {
                return !delegate.isEmpty();
            }
        },
        /** Show the loading item regardless of the empty state. */
        SHOW_ALWAYS {
            @Override
            boolean shouldShow(@NonNull Delegate delegate) {
                return true;
            }
        };

        abstract boolean shouldShow(@NonNull Delegate delegate);
    }

    /** Invoked by {@link LoadingAdapter} to determine when the loading item is shown. */
    public static abstract class Delegate {

        @Nullable
        private LoadingAdapter mAdapter;

        /**
         * Returns whether the loading item should be shown or not.
         * Invoke {@link #notifyLoadingChanged()} to inform the owning adapter if the empty state has changed.
         * @return {@code true} if the item should be shown, otherwise {@code false}.
         * @see #notifyLoadingChanged()
         */
        @UiThread
        protected abstract boolean isLoading();

        /**
         * Returns whether the data set is considered empty, for the purpose of evaluating the {@link
         * LoadingAdapterBuilder.EmptyPolicy}.
         * @return {@code true} if the data set is empty, otherwise {@code false}.
         */
        @UiThread
        protected abstract boolean isEmpty();

        /**
         * @see PowerAdapterWrapper#onFirstObserverRegistered()
         */
        @UiThread
        protected void onFirstObserverRegistered() {
        }

        /**
         * @see PowerAdapterWrapper#onLastObserverUnregistered()
         */
        @UiThread
        protected void onLastObserverUnregistered() {
        }

        /** Must be called when the value of {@link #isLoading()} changes. */
        @UiThread
        protected final void notifyLoadingChanged() {
            if (mAdapter != null) {
                mAdapter.updateVisible();
            }
        }

        /** Must be called when the value of {@link #isEmpty()} changes. */
        protected final void notifyEmptyChanged() {
            if (mAdapter != null) {
                mAdapter.updateVisible();
            }
        }

        void setAdapter(@Nullable LoadingAdapter adapter) {
            mAdapter = adapter;
        }
    }
}