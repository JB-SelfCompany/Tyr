package com.jbselfcompany.tyr.chat.ui

import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * Item animator that gives new chat messages a slide-up + fade-in entrance.
 * Move and remove animations are left as DefaultItemAnimator defaults.
 */
class ChatItemAnimator : DefaultItemAnimator() {

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        val view = holder.itemView
        view.alpha = 0f
        view.translationY = view.context.resources.displayMetrics.density * 24f

        dispatchAddStarting(holder)

        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { dispatchAddFinished(holder) }
            .start()

        return true
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        item.itemView.animate().cancel()
        item.itemView.alpha = 1f
        item.itemView.translationY = 0f
        super.endAnimation(item)
    }
}
