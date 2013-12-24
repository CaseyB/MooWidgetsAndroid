package org.moo.widgets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.support.v4.os.ParcelableCompat;
import android.support.v4.os.ParcelableCompatCreatorCallbacks;
import android.support.v4.view.KeyEventCompat;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.VelocityTrackerCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.ViewConfigurationCompat;
import android.support.v4.widget.EdgeEffectCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.Interpolator;
import android.widget.Scroller;

public class InfiniteViewPager extends ViewGroup
{
	private static final String TAG = "ViewPager";
	private static final boolean DEBUG = false;
	private static final boolean USE_CACHE = false;

	private static final int DEFAULT_OFFSCREEN_PAGES = 1;
	private static final int MAX_SETTLE_DURATION = 600; // ms

	static class ItemInfo
	{
		Object object;
		int position;
		boolean scrolling;
	}

	private static final Comparator<ItemInfo> COMPARATOR = new Comparator<ItemInfo>()
	{
		@Override
		public int compare(ItemInfo lhs, ItemInfo rhs)
		{
			return lhs.position - rhs.position;
		}
	};

	private static final Interpolator _interpolator = new Interpolator()
	{
		public float getInterpolation(float t)
		{
			t -= 1.0f;
			return t * t * t + 1.0f;
		}
	};

	private final ArrayList<ItemInfo> _items = new ArrayList<ItemInfo>();

	private InfinitePagerAdapter _adapter;
	private int _currItem;
	private int _restoredCurrItem = -1;
	private Parcelable _restoredAdapterState = null;
	private ClassLoader _restoredClassLoader = null;
	private Scroller _scroller;
	private DataSetObserver _observer;

	private int _pageMargin;
	private Drawable _marginDrawable;

	private int _childWidthMeasureSpec;
	private int _childHeightMeasureSpec;
	private boolean _inLayout;

	private boolean _scrollingCacheEnabled;

	private boolean _populatePending;
	private boolean _scrolling;
	private int _offscreenPageLimit = DEFAULT_OFFSCREEN_PAGES;

	private boolean _isBeingDragged;
	private boolean _isUnableToDrag;
	private int _touchSlop;
	private float _initialMotionX;

	private float _lastMotionX;
	private float _lastMotionY;

	private int _activePointerId = INVALID_POINTER;
	private static final int INVALID_POINTER = -1;

	private VelocityTracker _velocityTracker;
	private int _minimumVelocity;
	private int _maximumVelocity;
	private float _baseLineFlingVelocity;
	private float _flingVelocityInfluence;

	private boolean _fakeDragging;
	private long _fakeDragBeginTime;

	private EdgeEffectCompat _leftEdge;
	private EdgeEffectCompat mRightEdge;

	private boolean _firstLayout = true;
	private OnPageChangeListener _onPageChangeListener;

	public static final int SCROLL_STATE_IDLE = 0;
	public static final int SCROLL_STATE_DRAGGING = 1;
	public static final int SCROLL_STATE_SETTLING = 2;

	private int _scrollState = SCROLL_STATE_IDLE;

	public interface OnPageChangeListener
	{
		public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels);

		public void onPageSelected(int position);

		public void onPageScrollStateChanged(int state);
	}

	public InfiniteViewPager(Context context)
	{
		super(context);
	}

	public InfiniteViewPager(Context context, AttributeSet attrs)
	{
		super(context, attrs);
		initPager();
	}

	public InfiniteViewPager(Context context, AttributeSet attrs, int defStyle)
	{
		super(context, attrs, defStyle);
		initPager();
	}

	private void initPager()
	{
		setWillNotDraw(false);
		setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
		setFocusable(true);
		final Context context = getContext();
		_scroller = new Scroller(context, _interpolator);
		final ViewConfiguration configuration = ViewConfiguration.get(context);
		_touchSlop = ViewConfigurationCompat.getScaledPagingTouchSlop(configuration);
		_minimumVelocity = configuration.getScaledMinimumFlingVelocity();
		_maximumVelocity = configuration.getScaledMaximumFlingVelocity();
		_leftEdge = new EdgeEffectCompat(context);
		mRightEdge = new EdgeEffectCompat(context);

		float density = context.getResources().getDisplayMetrics().density;
		_baseLineFlingVelocity = 2500.0f * density;
		_flingVelocityInfluence = 0.4f;
	}

	private void setScrollState(int newState)
	{
		if(_scrollState == newState) return;

		_scrollState = newState;
		if(_onPageChangeListener != null)
		{
			_onPageChangeListener.onPageScrollStateChanged(newState);
		}
	}

	public void setAdapter(InfinitePagerAdapter adapter)
	{
		if(_adapter != null)
		{
			_adapter.setDataSetObserver(null);
			_adapter.startUpdate(this);
			for(int i = 0; i < _items.size(); i++)
			{
				final ItemInfo info = _items.get(i);
				_adapter.destroyItem(this, info.position, info.object);
			}
			_adapter.finishUpdate(this);
			_items.clear();
			removeAllViews();
			_currItem = 0;
			scrollTo(0, 0);
		}

		_adapter = adapter;

		if(_adapter != null)
		{
			if(_observer == null) _observer = new DataSetObserver();
			_adapter.setDataSetObserver(_observer);
			_populatePending = false;
			if(_restoredCurrItem >= 0)
			{
				_adapter.restoreState(_restoredAdapterState, _restoredClassLoader);
				setCurrentItemInternal(_restoredCurrItem, false, true);
				_restoredCurrItem = -1;
				_restoredAdapterState = null;
				_restoredClassLoader = null;
			}
			else
			{
				populate();
			}
		}
	}

	public InfinitePagerAdapter getAdapter()
	{
		return _adapter;
	}

	public void setCurrentItem(int item)
	{
		_populatePending = false;
		setCurrentItemInternal(item, !_firstLayout, false);
	}

	public void setCurrentItem(int item, boolean smoothScroll)
	{
		_populatePending = false;
		setCurrentItemInternal(item, smoothScroll, false);
	}

	void setCurrentItemInternal(int item, boolean smoothScroll, boolean always)
	{
		setCurrentItemInternal(item, smoothScroll, always, 0);
	}

	void setCurrentItemInternal(int item, boolean smoothScroll, boolean always, int velocity)
	{
		if(_adapter == null || _adapter.getCount() <= 0)
		{
			setScrollingCacheEnabled(false);
			return;
		}
		if(!always && _currItem == item && _items.size() != 0)
		{
			setScrollingCacheEnabled(false);
			return;
		}
		if(item < 0)
		{
			item = 0;
		}
		else if(item >= _adapter.getCount())
		{
			item = _adapter.getCount() - 1;
		}
		final int pageLimit = _offscreenPageLimit;
		if(item > (_currItem + pageLimit) || item < (_currItem - pageLimit))
		{
			// We are doing a jump by more than one page. To avoid
			// glitches, we want to keep all current pages in the view
			// until the scroll ends.
			for(int i = 0; i < _items.size(); i++)
			{
				_items.get(i).scrolling = true;
			}
		}
		final boolean dispatchSelected = _currItem != item;
		_currItem = item;
		populate();
		final int destX = (getWidth() + _pageMargin) * item;
		if(smoothScroll)
		{
			smoothScrollTo(destX, 0, velocity);
			if(dispatchSelected && _onPageChangeListener != null)
			{
				_onPageChangeListener.onPageSelected(item);
			}
		}
		else
		{
			if(dispatchSelected && _onPageChangeListener != null)
			{
				_onPageChangeListener.onPageSelected(item);
			}
			completeScroll();
			scrollTo(destX, 0);
		}
	}

	public void setOnPageChangeListener(OnPageChangeListener listener)
	{
		_onPageChangeListener = listener;
	}

	public int getOffscreenPageLimit()
	{
		return _offscreenPageLimit;
	}

	public void setOffscreenPageLimit(int limit)
	{
		if(limit < DEFAULT_OFFSCREEN_PAGES)
		{
			Log.w(TAG, "Requested offscreen page limit " + limit + " too small; defaulting to "
					+ DEFAULT_OFFSCREEN_PAGES);
			limit = DEFAULT_OFFSCREEN_PAGES;
		}
		if(limit != _offscreenPageLimit)
		{
			_offscreenPageLimit = limit;
			populate();
		}
	}

	/**
	 * Set the margin between pages.
	 * 
	 * @param marginPixels
	 *            Distance between adjacent pages in pixels
	 * @see #getPageMargin()
	 * @see #setPageMarginDrawable(Drawable)
	 * @see #setPageMarginDrawable(int)
	 */
	public void setPageMargin(int marginPixels)
	{
		final int oldMargin = _pageMargin;
		_pageMargin = marginPixels;

		final int width = getWidth();
		recomputeScrollPosition(width, width, marginPixels, oldMargin);

		requestLayout();
	}

	/**
	 * Return the margin between pages.
	 * 
	 * @return The size of the margin in pixels
	 */
	public int getPageMargin()
	{
		return _pageMargin;
	}

	/**
	 * Set a drawable that will be used to fill the margin between pages.
	 * 
	 * @param d
	 *            Drawable to display between pages
	 */
	public void setPageMarginDrawable(Drawable d)
	{
		_marginDrawable = d;
		if(d != null) refreshDrawableState();
		setWillNotDraw(d == null);
		invalidate();
	}

	/**
	 * Set a drawable that will be used to fill the margin between pages.
	 * 
	 * @param resId
	 *            Resource ID of a drawable to display between pages
	 */
	public void setPageMarginDrawable(int resId)
	{
		setPageMarginDrawable(getContext().getResources().getDrawable(resId));
	}

	@Override
	protected boolean verifyDrawable(Drawable who)
	{
		return super.verifyDrawable(who) || who == _marginDrawable;
	}

	@Override
	protected void drawableStateChanged()
	{
		super.drawableStateChanged();
		final Drawable d = _marginDrawable;
		if(d != null && d.isStateful())
		{
			d.setState(getDrawableState());
		}
	}

	// We want the duration of the page snap animation to be influenced by the
	// distance that
	// the screen has to travel, however, we don't want this duration to be
	// effected in a
	// purely linear fashion. Instead, we use this method to moderate the effect
	// that the distance
	// of travel has on the overall snap duration.
	float distanceInfluenceForSnapDuration(float f)
	{
		f -= 0.5f; // center the values about 0.
		f *= 0.3f * Math.PI / 2.0f;
		return (float) Math.sin(f);
	}

	/**
	 * Like {@link View#scrollBy}, but scroll smoothly instead of immediately.
	 * 
	 * @param x
	 *            the number of pixels to scroll by on the X axis
	 * @param y
	 *            the number of pixels to scroll by on the Y axis
	 */
	void smoothScrollTo(int x, int y)
	{
		smoothScrollTo(x, y, 0);
	}

	/**
	 * Like {@link View#scrollBy}, but scroll smoothly instead of immediately.
	 * 
	 * @param x
	 *            the number of pixels to scroll by on the X axis
	 * @param y
	 *            the number of pixels to scroll by on the Y axis
	 * @param velocity
	 *            the velocity associated with a fling, if applicable. (0
	 *            otherwise)
	 */
	void smoothScrollTo(int x, int y, int velocity)
	{
		if(getChildCount() == 0)
		{
			// Nothing to do.
			setScrollingCacheEnabled(false);
			return;
		}
		int sx = getScrollX();
		int sy = getScrollY();
		int dx = x - sx;
		int dy = y - sy;
		if(dx == 0 && dy == 0)
		{
			completeScroll();
			setScrollState(SCROLL_STATE_IDLE);
			return;
		}

		setScrollingCacheEnabled(true);
		_scrolling = true;
		setScrollState(SCROLL_STATE_SETTLING);

		final float pageDelta = (float) Math.abs(dx) / (getWidth() + _pageMargin);
		int duration = (int) (pageDelta * 100);

		velocity = Math.abs(velocity);
		if(velocity > 0)
		{
			duration += (duration / (velocity / _baseLineFlingVelocity)) * _flingVelocityInfluence;
		}
		else
		{
			duration += 100;
		}
		duration = Math.min(duration, MAX_SETTLE_DURATION);

		_scroller.startScroll(sx, sy, dx, dy, duration);
		invalidate();
	}

	void addNewItem(int position, int index)
	{
		ItemInfo ii = new ItemInfo();
		ii.position = position;
		ii.object = _adapter.instantiateItem(this, position);
		if(index < 0)
		{
			_items.add(ii);
		}
		else
		{
			_items.add(index, ii);
		}
	}

	void dataSetChanged()
	{
		// This method only gets called if our observer is attached, so _adapter
		// is non-null.

		boolean needPopulate = _items.size() < 3 && _items.size() < _adapter.getCount();
		int newCurrItem = -1;

		for(int i = 0; i < _items.size(); i++)
		{
			final ItemInfo ii = _items.get(i);
			final int newPos = _adapter.getItemPosition(ii.object);

			if(newPos == InfinitePagerAdapter.POSITION_UNCHANGED)
			{
				continue;
			}

			if(newPos == InfinitePagerAdapter.POSITION_NONE)
			{
				_items.remove(i);
				i--;
				_adapter.destroyItem(this, ii.position, ii.object);
				needPopulate = true;

				if(_currItem == ii.position)
				{
					// Keep the current item in the valid range
					newCurrItem = Math.max(0, Math.min(_currItem, _adapter.getCount() - 1));
				}
				continue;
			}

			if(ii.position != newPos)
			{
				if(ii.position == _currItem)
				{
					// Our current item changed position. Follow it.
					newCurrItem = newPos;
				}

				ii.position = newPos;
				needPopulate = true;
			}
		}

		Collections.sort(_items, COMPARATOR);

		if(newCurrItem >= 0)
		{
			// TODO This currently causes a jump.
			setCurrentItemInternal(newCurrItem, false, true);
			needPopulate = true;
		}
		if(needPopulate)
		{
			populate();
			requestLayout();
		}
	}

	void populate()
	{
		if(_adapter == null) { return; }

		// Bail now if we are waiting to populate. This is to hold off
		// on creating views from the time the user releases their finger to
		// fling to a new position until we have finished the scroll to
		// that position, avoiding glitches from happening at that point.
		if(_populatePending)
		{
			if(DEBUG) Log.i(TAG, "populate is pending, skipping for now...");
			return;
		}

		// Also, don't populate until we are attached to a window. This is to
		// avoid trying to populate before we have restored our view hierarchy
		// state and conflicting with what is restored.
		if(getWindowToken() == null) { return; }

		_adapter.startUpdate(this);

		final int pageLimit = _offscreenPageLimit;
		final int startPos = Math.max(0, _currItem - pageLimit);
		final int N = _adapter.getCount();
		final int endPos = Math.min(N - 1, _currItem + pageLimit);

		if(DEBUG) Log.v(TAG, "populating: startPos=" + startPos + " endPos=" + endPos);

		// Add and remove pages in the existing list.
		int lastPos = -1;
		for(int i = 0; i < _items.size(); i++)
		{
			ItemInfo ii = _items.get(i);
			if((ii.position < startPos || ii.position > endPos) && !ii.scrolling)
			{
				if(DEBUG) Log.i(TAG, "removing: " + ii.position + " @ " + i);
				_items.remove(i);
				i--;
				_adapter.destroyItem(this, ii.position, ii.object);
			}
			else if(lastPos < endPos && ii.position > startPos)
			{
				// The next item is outside of our range, but we have a gap
				// between it and the last item where we want to have a page
				// shown. Fill in the gap.
				lastPos++;
				if(lastPos < startPos)
				{
					lastPos = startPos;
				}
				while(lastPos <= endPos && lastPos < ii.position)
				{
					if(DEBUG) Log.i(TAG, "inserting: " + lastPos + " @ " + i);
					addNewItem(lastPos, i);
					lastPos++;
					i++;
				}
			}
			lastPos = ii.position;
		}

		// Add any new pages we need at the end.
		lastPos = _items.size() > 0 ? _items.get(_items.size() - 1).position : -1;
		if(lastPos < endPos)
		{
			lastPos++;
			lastPos = lastPos > startPos ? lastPos : startPos;
			while(lastPos <= endPos)
			{
				if(DEBUG) Log.i(TAG, "appending: " + lastPos);
				addNewItem(lastPos, -1);
				lastPos++;
			}
		}

		if(DEBUG)
		{
			Log.i(TAG, "Current page list:");
			for(int i = 0; i < _items.size(); i++)
			{
				Log.i(TAG, "#" + i + ": page " + _items.get(i).position);
			}
		}

		ItemInfo curItem = null;
		for(int i = 0; i < _items.size(); i++)
		{
			if(_items.get(i).position == _currItem)
			{
				curItem = _items.get(i);
				break;
			}
		}
		_adapter.setPrimaryItem(this, _currItem, curItem != null ? curItem.object : null);

		_adapter.finishUpdate(this);

		if(hasFocus())
		{
			View currentFocused = findFocus();
			ItemInfo ii = currentFocused != null ? infoForAnyChild(currentFocused) : null;
			if(ii == null || ii.position != _currItem)
			{
				for(int i = 0; i < getChildCount(); i++)
				{
					View child = getChildAt(i);
					ii = infoForChild(child);
					if(ii != null && ii.position == _currItem)
					{
						if(child.requestFocus(FOCUS_FORWARD))
						{
							break;
						}
					}
				}
			}
		}
	}

	public static class SavedState extends BaseSavedState
	{
		int position;
		Parcelable adapterState;
		ClassLoader loader;

		public SavedState(Parcelable superState)
		{
			super(superState);
		}

		@Override
		public void writeToParcel(Parcel out, int flags)
		{
			super.writeToParcel(out, flags);
			out.writeInt(position);
			out.writeParcelable(adapterState, flags);
		}

		@Override
		public String toString()
		{
			return "FragmentPager.SavedState{" + Integer.toHexString(System.identityHashCode(this))
					+ " position=" + position + "}";
		}

		public static final Parcelable.Creator<SavedState> CREATOR = ParcelableCompat
				.newCreator(new ParcelableCompatCreatorCallbacks<SavedState>()
				{
					@Override
					public SavedState createFromParcel(Parcel in, ClassLoader loader)
					{
						return new SavedState(in, loader);
					}

					@Override
					public SavedState[] newArray(int size)
					{
						return new SavedState[size];
					}
				});

		SavedState(Parcel in, ClassLoader loader)
		{
			super(in);
			if(loader == null)
			{
				loader = getClass().getClassLoader();
			}
			position = in.readInt();
			adapterState = in.readParcelable(loader);
			this.loader = loader;
		}
	}

	@Override
	public Parcelable onSaveInstanceState()
	{
		Parcelable superState = super.onSaveInstanceState();
		SavedState ss = new SavedState(superState);
		ss.position = _currItem;
		if(_adapter != null)
		{
			ss.adapterState = _adapter.saveState();
		}
		return ss;
	}

	@Override
	public void onRestoreInstanceState(Parcelable state)
	{
		if(!(state instanceof SavedState))
		{
			super.onRestoreInstanceState(state);
			return;
		}

		SavedState ss = (SavedState) state;
		super.onRestoreInstanceState(ss.getSuperState());

		if(_adapter != null)
		{
			_adapter.restoreState(ss.adapterState, ss.loader);
			setCurrentItemInternal(ss.position, false, true);
		}
		else
		{
			_restoredCurrItem = ss.position;
			_restoredAdapterState = ss.adapterState;
			_restoredClassLoader = ss.loader;
		}
	}

	@Override
	public void addView(View child, int index, LayoutParams params)
	{
		if(_inLayout)
		{
			addViewInLayout(child, index, params);
			child.measure(_childWidthMeasureSpec, _childHeightMeasureSpec);
		}
		else
		{
			super.addView(child, index, params);
		}

		if(USE_CACHE)
		{
			if(child.getVisibility() != GONE)
			{
				child.setDrawingCacheEnabled(_scrollingCacheEnabled);
			}
			else
			{
				child.setDrawingCacheEnabled(false);
			}
		}
	}

	ItemInfo infoForChild(View child)
	{
		for(int i = 0; i < _items.size(); i++)
		{
			ItemInfo ii = _items.get(i);
			if(_adapter.isViewFromObject(child, ii.object)) { return ii; }
		}
		return null;
	}

	ItemInfo infoForAnyChild(View child)
	{
		ViewParent parent;
		while((parent = child.getParent()) != this)
		{
			if(parent == null || !(parent instanceof View)) { return null; }
			child = (View) parent;
		}
		return infoForChild(child);
	}

	@Override
	protected void onAttachedToWindow()
	{
		super.onAttachedToWindow();
		_firstLayout = true;
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
	{
		// For simple implementation, or internal size is always 0.
		// We depend on the container to specify the layout size of
		// our view. We can't really know what it is since we will be
		// adding and removing different arbitrary views and do not
		// want the layout to change as this happens.
		setMeasuredDimension(getDefaultSize(0, widthMeasureSpec),
				getDefaultSize(0, heightMeasureSpec));

		// Children are just made to fill our space.
		_childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(getMeasuredWidth() - getPaddingLeft()
				- getPaddingRight(), MeasureSpec.EXACTLY);
		_childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(getMeasuredHeight() - getPaddingTop()
				- getPaddingBottom(), MeasureSpec.EXACTLY);

		// Make sure we have created all fragments that we need to have shown.
		_inLayout = true;
		populate();
		_inLayout = false;

		// Make sure all children have been properly measured.
		final int size = getChildCount();
		for(int i = 0; i < size; ++i)
		{
			final View child = getChildAt(i);
			if(child.getVisibility() != GONE)
			{
				if(DEBUG) Log.v(TAG, "Measuring #" + i + " " + child + ": "
						+ _childWidthMeasureSpec);
				child.measure(_childWidthMeasureSpec, _childHeightMeasureSpec);
			}
		}
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh)
	{
		super.onSizeChanged(w, h, oldw, oldh);

		// Make sure scroll position is set correctly.
		if(w != oldw)
		{
			recomputeScrollPosition(w, oldw, _pageMargin, _pageMargin);
		}
	}

	private void recomputeScrollPosition(int width, int oldWidth, int margin, int oldMargin)
	{
		final int widthWithMargin = width + margin;
		if(oldWidth > 0)
		{
			final int oldScrollPos = getScrollX();
			final int oldwwm = oldWidth + oldMargin;
			final int oldScrollItem = oldScrollPos / oldwwm;
			final float scrollOffset = (float) (oldScrollPos % oldwwm) / oldwwm;
			final int scrollPos = (int) ((oldScrollItem + scrollOffset) * widthWithMargin);
			scrollTo(scrollPos, getScrollY());
			if(!_scroller.isFinished())
			{
				// We now return to your regularly scheduled scroll, already in
				// progress.
				final int newDuration = _scroller.getDuration() - _scroller.timePassed();
				_scroller.startScroll(scrollPos, 0, _currItem * widthWithMargin, 0, newDuration);
			}
		}
		else
		{
			int scrollPos = _currItem * widthWithMargin;
			if(scrollPos != getScrollX())
			{
				completeScroll();
				scrollTo(scrollPos, getScrollY());
			}
		}
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b)
	{
		_inLayout = true;
		populate();
		_inLayout = false;

		final int count = getChildCount();
		final int width = r - l;

		for(int i = 0; i < count; i++)
		{
			View child = getChildAt(i);
			ItemInfo ii;
			if(child.getVisibility() != GONE && (ii = infoForChild(child)) != null)
			{
				int loff = (width + _pageMargin) * ii.position;
				int childLeft = getPaddingLeft() + loff;
				int childTop = getPaddingTop();
				if(DEBUG) Log.v(
						TAG,
						"Positioning #" + i + " " + child + " f=" + ii.object + ":" + childLeft
								+ "," + childTop + " " + child.getMeasuredWidth() + "x"
								+ child.getMeasuredHeight());
				child.layout(childLeft, childTop, childLeft + child.getMeasuredWidth(), childTop
						+ child.getMeasuredHeight());
			}
		}
		_firstLayout = false;
	}

	@Override
	public void computeScroll()
	{
		if(DEBUG) Log.i(TAG, "computeScroll: finished=" + _scroller.isFinished());
		if(!_scroller.isFinished())
		{
			if(_scroller.computeScrollOffset())
			{
				if(DEBUG) Log.i(TAG, "computeScroll: still scrolling");
				int oldX = getScrollX();
				int oldY = getScrollY();
				int x = _scroller.getCurrX();
				int y = _scroller.getCurrY();

				if(oldX != x || oldY != y)
				{
					scrollTo(x, y);
				}

				if(_onPageChangeListener != null)
				{
					final int widthWithMargin = getWidth() + _pageMargin;
					final int position = x / widthWithMargin;
					final int offsetPixels = x % widthWithMargin;
					final float offset = (float) offsetPixels / widthWithMargin;
					_onPageChangeListener.onPageScrolled(position, offset, offsetPixels);
				}

				// Keep on drawing until the animation has finished.
				invalidate();
				return;
			}
		}

		// Done with scroll, clean up state.
		completeScroll();
	}

	private void completeScroll()
	{
		boolean needPopulate = _scrolling;
		if(needPopulate)
		{
			// Done with scroll, no longer want to cache view drawing.
			setScrollingCacheEnabled(false);
			_scroller.abortAnimation();
			int oldX = getScrollX();
			int oldY = getScrollY();
			int x = _scroller.getCurrX();
			int y = _scroller.getCurrY();
			if(oldX != x || oldY != y)
			{
				scrollTo(x, y);
			}
			setScrollState(SCROLL_STATE_IDLE);
		}
		_populatePending = false;
		_scrolling = false;
		for(int i = 0; i < _items.size(); i++)
		{
			ItemInfo ii = _items.get(i);
			if(ii.scrolling)
			{
				needPopulate = true;
				ii.scrolling = false;
			}
		}
		if(needPopulate)
		{
			populate();
		}
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent ev)
	{
		/*
		 * This method JUST determines whether we want to intercept the motion.
		 * If we return true, onMotionEvent will be called and we do the actual
		 * scrolling there.
		 */

		final int action = ev.getAction() & MotionEventCompat.ACTION_MASK;

		// Always take care of the touch gesture being complete.
		if(action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP)
		{
			// Release the drag.
			if(DEBUG) Log.v(TAG, "Intercept done!");
			_isBeingDragged = false;
			_isUnableToDrag = false;
			_activePointerId = INVALID_POINTER;
			return false;
		}

		// Nothing more to do here if we have decided whether or not we
		// are dragging.
		if(action != MotionEvent.ACTION_DOWN)
		{
			if(_isBeingDragged)
			{
				if(DEBUG) Log.v(TAG, "Intercept returning true!");
				return true;
			}
			if(_isUnableToDrag)
			{
				if(DEBUG) Log.v(TAG, "Intercept returning false!");
				return false;
			}
		}

		switch(action)
		{
		case MotionEvent.ACTION_MOVE:
		{
			/*
			 * _isBeingDragged == false, otherwise the shortcut would have
			 * caught it. Check whether the user has moved far enough from his
			 * original down touch.
			 */

			/*
			 * Locally do absolute value. _lastMotionY is set to the y value of
			 * the down event.
			 */
			final int activePointerId = _activePointerId;
			if(activePointerId == INVALID_POINTER)
			{
				// If we don't have a valid id, the touch down wasn't on
				// content.
				break;
			}

			final int pointerIndex = MotionEventCompat.findPointerIndex(ev, activePointerId);
			final float x = MotionEventCompat.getX(ev, pointerIndex);
			final float dx = x - _lastMotionX;
			final float xDiff = Math.abs(dx);
			final float y = MotionEventCompat.getY(ev, pointerIndex);
			final float yDiff = Math.abs(y - _lastMotionY);
			final int scrollX = getScrollX();
			final boolean atEdge = (dx > 0 && scrollX == 0)
					|| (dx < 0 && _adapter != null && scrollX >= (_adapter.getCount() - 1)
							* getWidth() - 1);
			if(DEBUG) Log.v(TAG, "Moved x to " + x + "," + y + " diff=" + xDiff + "," + yDiff);

			if(canScroll(this, false, (int) dx, (int) x, (int) y))
			{
				// Nested view has scrollable area under this point. Let it be
				// handled there.
				_initialMotionX = _lastMotionX = x;
				_lastMotionY = y;
				return false;
			}
			if(xDiff > _touchSlop && xDiff > yDiff)
			{
				if(DEBUG) Log.v(TAG, "Starting drag!");
				_isBeingDragged = true;
				setScrollState(SCROLL_STATE_DRAGGING);
				_lastMotionX = x;
				setScrollingCacheEnabled(true);
			}
			else
			{
				if(yDiff > _touchSlop)
				{
					// The finger has moved enough in the vertical
					// direction to be counted as a drag... abort
					// any attempt to drag horizontally, to work correctly
					// with children that have scrolling containers.
					if(DEBUG) Log.v(TAG, "Starting unable to drag!");
					_isUnableToDrag = true;
				}
			}
			break;
		}

		case MotionEvent.ACTION_DOWN:
		{
			/*
			 * Remember location of down touch. ACTION_DOWN always refers to
			 * pointer index 0.
			 */
			_lastMotionX = _initialMotionX = ev.getX();
			_lastMotionY = ev.getY();
			_activePointerId = MotionEventCompat.getPointerId(ev, 0);

			if(_scrollState == SCROLL_STATE_SETTLING)
			{
				// Let the user 'catch' the pager as it animates.
				_isBeingDragged = true;
				_isUnableToDrag = false;
				setScrollState(SCROLL_STATE_DRAGGING);
			}
			else
			{
				completeScroll();
				_isBeingDragged = false;
				_isUnableToDrag = false;
			}

			if(DEBUG) Log.v(TAG, "Down at " + _lastMotionX + "," + _lastMotionY
					+ " _isBeingDragged=" + _isBeingDragged + "_isUnableToDrag=" + _isUnableToDrag);
			break;
		}

		case MotionEventCompat.ACTION_POINTER_UP:
			onSecondaryPointerUp(ev);
			break;
		}

		/*
		 * The only time we want to intercept motion events is if we are in the
		 * drag mode.
		 */
		return _isBeingDragged;
	}

	@Override
	public boolean onTouchEvent(MotionEvent ev)
	{
		if(_fakeDragging)
		{
			// A fake drag is in progress already, ignore this real one
			// but still eat the touch events.
			// (It is likely that the user is multi-touching the screen.)
			return true;
		}

		if(ev.getAction() == MotionEvent.ACTION_DOWN && ev.getEdgeFlags() != 0)
		{
			// Don't handle edge touches immediately -- they may actually belong
			// to one of our
			// descendants.
			return false;
		}

		if(_adapter == null || _adapter.getCount() == 0)
		{
			// Nothing to present or scroll; nothing to touch.
			return false;
		}

		if(_velocityTracker == null)
		{
			_velocityTracker = VelocityTracker.obtain();
		}
		_velocityTracker.addMovement(ev);

		final int action = ev.getAction();
		boolean needsInvalidate = false;

		switch(action & MotionEventCompat.ACTION_MASK)
		{
		case MotionEvent.ACTION_DOWN:
		{
			/*
			 * If being flinged and user touches, stop the fling. isFinished
			 * will be false if being flinged.
			 */
			completeScroll();

			// Remember where the motion event started
			_lastMotionX = _initialMotionX = ev.getX();
			_activePointerId = MotionEventCompat.getPointerId(ev, 0);
			break;
		}
		case MotionEvent.ACTION_MOVE:
			if(!_isBeingDragged)
			{
				final int pointerIndex = MotionEventCompat.findPointerIndex(ev, _activePointerId);
				final float x = MotionEventCompat.getX(ev, pointerIndex);
				final float xDiff = Math.abs(x - _lastMotionX);
				final float y = MotionEventCompat.getY(ev, pointerIndex);
				final float yDiff = Math.abs(y - _lastMotionY);
				if(DEBUG) Log.v(TAG, "Moved x to " + x + "," + y + " diff=" + xDiff + "," + yDiff);
				if(xDiff > _touchSlop && xDiff > yDiff)
				{
					if(DEBUG) Log.v(TAG, "Starting drag!");
					_isBeingDragged = true;
					_lastMotionX = x;
					setScrollState(SCROLL_STATE_DRAGGING);
					setScrollingCacheEnabled(true);
				}
			}
			if(_isBeingDragged)
			{
				// Scroll to follow the motion event
				final int activePointerIndex = MotionEventCompat.findPointerIndex(ev,
						_activePointerId);
				final float x = MotionEventCompat.getX(ev, activePointerIndex);
				final float deltaX = _lastMotionX - x;
				_lastMotionX = x;
				float oldScrollX = getScrollX();
				float scrollX = oldScrollX + deltaX;
				final int width = getWidth();
				final int widthWithMargin = width + _pageMargin;

				final int lastItemIndex = _adapter.getCount() - 1;
				final float leftBound = Math.max(0, (_currItem - 1) * widthWithMargin);
				final float rightBound = Math.min(_currItem + 1, lastItemIndex) * widthWithMargin;
				if(scrollX < leftBound)
				{
					if(leftBound == 0)
					{
						float over = -scrollX;
						needsInvalidate = _leftEdge.onPull(over / width);
					}
					scrollX = leftBound;
				}
				else if(scrollX > rightBound)
				{
					if(rightBound == lastItemIndex * widthWithMargin)
					{
						float over = scrollX - rightBound;
						needsInvalidate = mRightEdge.onPull(over / width);
					}
					scrollX = rightBound;
				}
				// Don't lose the rounded component
				_lastMotionX += scrollX - (int) scrollX;
				scrollTo((int) scrollX, getScrollY());
				if(_onPageChangeListener != null)
				{
					final int position = (int) scrollX / widthWithMargin;
					final int positionOffsetPixels = (int) scrollX % widthWithMargin;
					final float positionOffset = (float) positionOffsetPixels / widthWithMargin;
					_onPageChangeListener.onPageScrolled(position, positionOffset,
							positionOffsetPixels);
				}
			}
			break;
		case MotionEvent.ACTION_UP:
			if(_isBeingDragged)
			{
				final VelocityTracker velocityTracker = _velocityTracker;
				velocityTracker.computeCurrentVelocity(1000, _maximumVelocity);
				int initialVelocity = (int) VelocityTrackerCompat.getXVelocity(velocityTracker,
						_activePointerId);
				_populatePending = true;
				final int widthWithMargin = getWidth() + _pageMargin;
				final int scrollX = getScrollX();
				final int currentPage = scrollX / widthWithMargin;
				int nextPage = initialVelocity > 0 ? currentPage : currentPage + 1;
				setCurrentItemInternal(nextPage, true, true, initialVelocity);

				_activePointerId = INVALID_POINTER;
				endDrag();
				needsInvalidate = _leftEdge.onRelease() | mRightEdge.onRelease();
			}
			break;
		case MotionEvent.ACTION_CANCEL:
			if(_isBeingDragged)
			{
				setCurrentItemInternal(_currItem, true, true);
				_activePointerId = INVALID_POINTER;
				endDrag();
				needsInvalidate = _leftEdge.onRelease() | mRightEdge.onRelease();
			}
			break;
		case MotionEventCompat.ACTION_POINTER_DOWN:
		{
			final int index = MotionEventCompat.getActionIndex(ev);
			final float x = MotionEventCompat.getX(ev, index);
			_lastMotionX = x;
			_activePointerId = MotionEventCompat.getPointerId(ev, index);
			break;
		}
		case MotionEventCompat.ACTION_POINTER_UP:
			onSecondaryPointerUp(ev);
			_lastMotionX = MotionEventCompat.getX(ev,
					MotionEventCompat.findPointerIndex(ev, _activePointerId));
			break;
		}
		if(needsInvalidate)
		{
			invalidate();
		}
		return true;
	}

	@Override
	public void draw(Canvas canvas)
	{
		super.draw(canvas);
		boolean needsInvalidate = false;

		final int overScrollMode = ViewCompat.getOverScrollMode(this);
		if(overScrollMode == ViewCompat.OVER_SCROLL_ALWAYS
				|| (overScrollMode == ViewCompat.OVER_SCROLL_IF_CONTENT_SCROLLS && _adapter != null && _adapter
						.getCount() > 1))
		{
			if(!_leftEdge.isFinished())
			{
				final int restoreCount = canvas.save();
				final int height = getHeight() - getPaddingTop() - getPaddingBottom();

				canvas.rotate(270);
				canvas.translate(-height + getPaddingTop(), 0);
				_leftEdge.setSize(height, getWidth());
				needsInvalidate |= _leftEdge.draw(canvas);
				canvas.restoreToCount(restoreCount);
			}
			if(!mRightEdge.isFinished())
			{
				final int restoreCount = canvas.save();
				final int width = getWidth();
				final int height = getHeight() - getPaddingTop() - getPaddingBottom();
				final int itemCount = _adapter != null ? _adapter.getCount() : 1;

				canvas.rotate(90);
				canvas.translate(-getPaddingTop(), -itemCount * (width + _pageMargin) + _pageMargin);
				mRightEdge.setSize(height, width);
				needsInvalidate |= mRightEdge.draw(canvas);
				canvas.restoreToCount(restoreCount);
			}
		}
		else
		{
			_leftEdge.finish();
			mRightEdge.finish();
		}

		if(needsInvalidate)
		{
			// Keep animating
			invalidate();
		}
	}

	@Override
	protected void onDraw(Canvas canvas)
	{
		super.onDraw(canvas);

		// Draw the margin drawable if needed.
		if(_pageMargin > 0 && _marginDrawable != null)
		{
			final int scrollX = getScrollX();
			final int width = getWidth();
			final int offset = scrollX % (width + _pageMargin);
			if(offset != 0)
			{
				// Pages fit completely when settled; we only need to draw when
				// in between
				final int left = scrollX - offset + width;
				_marginDrawable.setBounds(left, 0, left + _pageMargin, getHeight());
				_marginDrawable.draw(canvas);
			}
		}
	}

	/**
	 * Start a fake drag of the pager.
	 * 
	 * <p>
	 * A fake drag can be useful if you want to synchronize the motion of the
	 * ViewPager with the touch scrolling of another view, while still letting
	 * the ViewPager control the snapping motion and fling behavior. (e.g.
	 * parallax-scrolling tabs.) Call {@link #fakeDragBy(float)} to simulate the
	 * actual drag motion. Call {@link #endFakeDrag()} to complete the fake drag
	 * and fling as necessary.
	 * 
	 * <p>
	 * During a fake drag the ViewPager will ignore all touch events. If a real
	 * drag is already in progress, this method will return false.
	 * 
	 * @return true if the fake drag began successfully, false if it could not
	 *         be started.
	 * 
	 * @see #fakeDragBy(float)
	 * @see #endFakeDrag()
	 */
	public boolean beginFakeDrag()
	{
		if(_isBeingDragged) { return false; }
		_fakeDragging = true;
		setScrollState(SCROLL_STATE_DRAGGING);
		_initialMotionX = _lastMotionX = 0;
		if(_velocityTracker == null)
		{
			_velocityTracker = VelocityTracker.obtain();
		}
		else
		{
			_velocityTracker.clear();
		}
		final long time = SystemClock.uptimeMillis();
		final MotionEvent ev = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 0, 0, 0);
		_velocityTracker.addMovement(ev);
		ev.recycle();
		_fakeDragBeginTime = time;
		return true;
	}

	/**
	 * End a fake drag of the pager.
	 * 
	 * @see #beginFakeDrag()
	 * @see #fakeDragBy(float)
	 */
	public void endFakeDrag()
	{
		if(!_fakeDragging) { throw new IllegalStateException(
				"No fake drag in progress. Call beginFakeDrag first."); }

		final VelocityTracker velocityTracker = _velocityTracker;
		velocityTracker.computeCurrentVelocity(1000, _maximumVelocity);
		int initialVelocity = (int) VelocityTrackerCompat.getYVelocity(velocityTracker,
				_activePointerId);
		_populatePending = true;
		if((Math.abs(initialVelocity) > _minimumVelocity)
				|| Math.abs(_initialMotionX - _lastMotionX) >= (getWidth() / 3))
		{
			if(_lastMotionX > _initialMotionX)
			{
				setCurrentItemInternal(_currItem - 1, true, true);
			}
			else
			{
				setCurrentItemInternal(_currItem + 1, true, true);
			}
		}
		else
		{
			setCurrentItemInternal(_currItem, true, true);
		}
		endDrag();

		_fakeDragging = false;
	}

	/**
	 * Fake drag by an offset in pixels. You must have called
	 * {@link #beginFakeDrag()} first.
	 * 
	 * @param xOffset
	 *            Offset in pixels to drag by.
	 * @see #beginFakeDrag()
	 * @see #endFakeDrag()
	 */
	public void fakeDragBy(float xOffset)
	{
		if(!_fakeDragging) { throw new IllegalStateException(
				"No fake drag in progress. Call beginFakeDrag first."); }

		_lastMotionX += xOffset;
		float scrollX = getScrollX() - xOffset;
		final int width = getWidth();
		final int widthWithMargin = width + _pageMargin;

		final float leftBound = Math.max(0, (_currItem - 1) * widthWithMargin);
		final float rightBound = Math.min(_currItem + 1, _adapter.getCount() - 1) * widthWithMargin;
		if(scrollX < leftBound)
		{
			scrollX = leftBound;
		}
		else if(scrollX > rightBound)
		{
			scrollX = rightBound;
		}
		// Don't lose the rounded component
		_lastMotionX += scrollX - (int) scrollX;
		scrollTo((int) scrollX, getScrollY());
		if(_onPageChangeListener != null)
		{
			final int position = (int) scrollX / widthWithMargin;
			final int positionOffsetPixels = (int) scrollX % widthWithMargin;
			final float positionOffset = (float) positionOffsetPixels / widthWithMargin;
			_onPageChangeListener.onPageScrolled(position, positionOffset, positionOffsetPixels);
		}

		// Synthesize an event for the VelocityTracker.
		final long time = SystemClock.uptimeMillis();
		final MotionEvent ev = MotionEvent.obtain(_fakeDragBeginTime, time,
				MotionEvent.ACTION_MOVE, _lastMotionX, 0, 0);
		_velocityTracker.addMovement(ev);
		ev.recycle();
	}

	/**
	 * Returns true if a fake drag is in progress.
	 * 
	 * @return true if currently in a fake drag, false otherwise.
	 * 
	 * @see #beginFakeDrag()
	 * @see #fakeDragBy(float)
	 * @see #endFakeDrag()
	 */
	public boolean isFakeDragging()
	{
		return _fakeDragging;
	}

	private void onSecondaryPointerUp(MotionEvent ev)
	{
		final int pointerIndex = MotionEventCompat.getActionIndex(ev);
		final int pointerId = MotionEventCompat.getPointerId(ev, pointerIndex);
		if(pointerId == _activePointerId)
		{
			// This was our active pointer going up. Choose a new
			// active pointer and adjust accordingly.
			final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
			_lastMotionX = MotionEventCompat.getX(ev, newPointerIndex);
			_activePointerId = MotionEventCompat.getPointerId(ev, newPointerIndex);
			if(_velocityTracker != null)
			{
				_velocityTracker.clear();
			}
		}
	}

	private void endDrag()
	{
		_isBeingDragged = false;
		_isUnableToDrag = false;

		if(_velocityTracker != null)
		{
			_velocityTracker.recycle();
			_velocityTracker = null;
		}
	}

	private void setScrollingCacheEnabled(boolean enabled)
	{
		if(_scrollingCacheEnabled != enabled)
		{
			_scrollingCacheEnabled = enabled;
			if(USE_CACHE)
			{
				final int size = getChildCount();
				for(int i = 0; i < size; ++i)
				{
					final View child = getChildAt(i);
					if(child.getVisibility() != GONE)
					{
						child.setDrawingCacheEnabled(enabled);
					}
				}
			}
		}
	}

	/**
	 * Tests scrollability within child views of v given a delta of dx.
	 * 
	 * @param v
	 *            View to test for horizontal scrollability
	 * @param checkV
	 *            Whether the view v passed should itself be checked for
	 *            scrollability (true), or just its children (false).
	 * @param dx
	 *            Delta scrolled in pixels
	 * @param x
	 *            X coordinate of the active touch point
	 * @param y
	 *            Y coordinate of the active touch point
	 * @return true if child views of v can be scrolled by delta of dx.
	 */
	protected boolean canScroll(View v, boolean checkV, int dx, int x, int y)
	{
		if(v instanceof ViewGroup)
		{
			final ViewGroup group = (ViewGroup) v;
			final int scrollX = v.getScrollX();
			final int scrollY = v.getScrollY();
			final int count = group.getChildCount();
			// Count backwards - let topmost views consume scroll distance
			// first.
			for(int i = count - 1; i >= 0; i--)
			{
				// TODO: Add versioned support here for transformed views.
				// This will not work for transformed views in Honeycomb+
				final View child = group.getChildAt(i);
				if(x + scrollX >= child.getLeft()
						&& x + scrollX < child.getRight()
						&& y + scrollY >= child.getTop()
						&& y + scrollY < child.getBottom()
						&& canScroll(child, true, dx, x + scrollX - child.getLeft(), y + scrollY
								- child.getTop())) { return true; }
			}
		}

		return checkV && ViewCompat.canScrollHorizontally(v, -dx);
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event)
	{
		// Let the focused view and/or our descendants get the key first
		return super.dispatchKeyEvent(event) || executeKeyEvent(event);
	}

	/**
	 * You can call this function yourself to have the scroll view perform
	 * scrolling from a key event, just as if the event had been dispatched to
	 * it by the view hierarchy.
	 * 
	 * @param event
	 *            The key event to execute.
	 * @return Return true if the event was handled, else false.
	 */
	public boolean executeKeyEvent(KeyEvent event)
	{
		boolean handled = false;
		if(event.getAction() == KeyEvent.ACTION_DOWN)
		{
			switch(event.getKeyCode())
			{
			case KeyEvent.KEYCODE_DPAD_LEFT:
				handled = arrowScroll(FOCUS_LEFT);
				break;
			case KeyEvent.KEYCODE_DPAD_RIGHT:
				handled = arrowScroll(FOCUS_RIGHT);
				break;
			case KeyEvent.KEYCODE_TAB:
				if(KeyEventCompat.hasNoModifiers(event))
				{
					handled = arrowScroll(FOCUS_FORWARD);
				}
				else if(KeyEventCompat.hasModifiers(event, KeyEvent.META_SHIFT_ON))
				{
					handled = arrowScroll(FOCUS_BACKWARD);
				}
				break;
			}
		}
		return handled;
	}

	public boolean arrowScroll(int direction)
	{
		View currentFocused = findFocus();
		if(currentFocused == this) currentFocused = null;

		boolean handled = false;

		View nextFocused = FocusFinder.getInstance().findNextFocus(this, currentFocused, direction);
		if(nextFocused != null && nextFocused != currentFocused)
		{
			if(direction == View.FOCUS_LEFT)
			{
				// If there is nothing to the left, or this is causing us to
				// jump to the right, then what we really want to do is page
				// left.
				if(currentFocused != null && nextFocused.getLeft() >= currentFocused.getLeft())
				{
					handled = pageLeft();
				}
				else
				{
					handled = nextFocused.requestFocus();
				}
			}
			else if(direction == View.FOCUS_RIGHT)
			{
				// If there is nothing to the right, or this is causing us to
				// jump to the left, then what we really want to do is page
				// right.
				if(currentFocused != null && nextFocused.getLeft() <= currentFocused.getLeft())
				{
					handled = pageRight();
				}
				else
				{
					handled = nextFocused.requestFocus();
				}
			}
		}
		else if(direction == FOCUS_LEFT || direction == FOCUS_BACKWARD)
		{
			// Trying to move left and nothing there; try to page.
			handled = pageLeft();
		}
		else if(direction == FOCUS_RIGHT || direction == FOCUS_FORWARD)
		{
			// Trying to move right and nothing there; try to page.
			handled = pageRight();
		}
		if(handled)
		{
			playSoundEffect(SoundEffectConstants.getContantForFocusDirection(direction));
		}
		return handled;
	}

	boolean pageLeft()
	{
		if(_currItem > 0)
		{
			setCurrentItem(_currItem - 1, true);
			return true;
		}
		return false;
	}

	boolean pageRight()
	{
		if(_adapter != null && _currItem < (_adapter.getCount() - 1))
		{
			setCurrentItem(_currItem + 1, true);
			return true;
		}
		return false;
	}

	/**
	 * We only want the current page that is being shown to be focusable.
	 */
	@Override
	public void addFocusables(ArrayList<View> views, int direction, int focusableMode)
	{
		final int focusableCount = views.size();

		final int descendantFocusability = getDescendantFocusability();

		if(descendantFocusability != FOCUS_BLOCK_DESCENDANTS)
		{
			for(int i = 0; i < getChildCount(); i++)
			{
				final View child = getChildAt(i);
				if(child.getVisibility() == VISIBLE)
				{
					ItemInfo ii = infoForChild(child);
					if(ii != null && ii.position == _currItem)
					{
						child.addFocusables(views, direction, focusableMode);
					}
				}
			}
		}

		// we add ourselves (if focusable) in all cases except for when we are
		// FOCUS_AFTER_DESCENDANTS and there are some descendants focusable.
		// this is
		// to avoid the focus search finding layouts when a more precise search
		// among the focusable children would be more interesting.
		if(descendantFocusability != FOCUS_AFTER_DESCENDANTS ||
		// No focusable descendants
				(focusableCount == views.size()))
		{
			// Note that we can't call the superclass here, because it will
			// add all views in. So we need to do the same thing View does.
			if(!isFocusable()) { return; }
			if((focusableMode & FOCUSABLES_TOUCH_MODE) == FOCUSABLES_TOUCH_MODE && isInTouchMode()
					&& !isFocusableInTouchMode()) { return; }
			if(views != null)
			{
				views.add(this);
			}
		}
	}

	/**
	 * We only want the current page that is being shown to be touchable.
	 */
	@Override
	public void addTouchables(ArrayList<View> views)
	{
		// Note that we don't call super.addTouchables(), which means that
		// we don't call View.addTouchables(). This is okay because a ViewPager
		// is itself not touchable.
		for(int i = 0; i < getChildCount(); i++)
		{
			final View child = getChildAt(i);
			if(child.getVisibility() == VISIBLE)
			{
				ItemInfo ii = infoForChild(child);
				if(ii != null && ii.position == _currItem)
				{
					child.addTouchables(views);
				}
			}
		}
	}

	/**
	 * We only want the current page that is being shown to be focusable.
	 */
	@Override
	protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect)
	{
		int index;
		int increment;
		int end;
		int count = getChildCount();
		if((direction & FOCUS_FORWARD) != 0)
		{
			index = 0;
			increment = 1;
			end = count;
		}
		else
		{
			index = count - 1;
			increment = -1;
			end = -1;
		}
		for(int i = index; i != end; i += increment)
		{
			View child = getChildAt(i);
			if(child.getVisibility() == VISIBLE)
			{
				ItemInfo ii = infoForChild(child);
				if(ii != null && ii.position == _currItem)
				{
					if(child.requestFocus(direction, previouslyFocusedRect)) { return true; }
				}
			}
		}
		return false;
	}

	@Override
	public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event)
	{
		// ViewPagers should only report accessibility info for the current
		// page,
		// otherwise things get very confusing.

		// TODO: Should this note something about the paging container?

		final int childCount = getChildCount();
		for(int i = 0; i < childCount; i++)
		{
			final View child = getChildAt(i);
			if(child.getVisibility() == VISIBLE)
			{
				final ItemInfo ii = infoForChild(child);
				if(ii != null && ii.position == _currItem
						&& child.dispatchPopulateAccessibilityEvent(event)) { return true; }
			}
		}

		return false;
	}

	private class DataSetObserver implements InfinitePagerAdapter.DataSetObserver
	{
		@Override
		public void onDataSetChanged()
		{
			dataSetChanged();
		}
	}
}
