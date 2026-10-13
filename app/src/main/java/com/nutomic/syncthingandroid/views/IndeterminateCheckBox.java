package com.nutomic.syncthingandroid.views;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.core.widget.CompoundButtonCompat;

import com.nutomic.syncthingandroid.R;

/**
 * 支持"部分选中"（indeterminate）状态的复选框。
 *
 * <p>Android 的 CheckBox 只有选中/未选中两态，用于目录树时无法表达
 * "子项里有一部分被选中"。这里通过切换 buttonDrawable 实现第三种状态。
 */
public class IndeterminateCheckBox extends AppCompatCheckBox {

    private boolean mIndeterminate;

    private Drawable mDefaultButtonDrawable;

    public IndeterminateCheckBox(Context context) {
        this(context, null);
    }

    public IndeterminateCheckBox(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.checkboxStyle);
    }

    public IndeterminateCheckBox(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public boolean isIndeterminate() {
        return mIndeterminate;
    }

    /**
     * @param indeterminate true 显示"横杠"图标，表示子项中只有部分被选中
     */
    public void setIndeterminate(boolean indeterminate) {
        if (mIndeterminate == indeterminate) {
            return;
        }
        mIndeterminate = indeterminate;
        if (indeterminate) {
            if (mDefaultButtonDrawable == null) {
                mDefaultButtonDrawable = CompoundButtonCompat.getButtonDrawable(this);
            }
            Drawable mark = AppCompatResources.getDrawable(
                    getContext(), R.drawable.ic_indeterminate_checkbox);
            setButtonDrawable(mark);
        } else if (mDefaultButtonDrawable != null) {
            setButtonDrawable(mDefaultButtonDrawable);
        }
        invalidate();
    }
}
