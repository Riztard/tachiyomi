package eu.kanade.tachiyomi.ui.browse.source.index

import android.graphics.Color
import android.view.View
import com.bumptech.glide.load.engine.DiskCacheStrategy
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.glide.GlideApp
import eu.kanade.tachiyomi.data.glide.toMangaThumbnail
import eu.kanade.tachiyomi.databinding.GlobalSearchControllerCardItemBinding
import eu.kanade.tachiyomi.widget.StateImageViewTarget

class IndexCardHolder(view: View, adapter: IndexCardAdapter) :
    FlexibleViewHolder(view, adapter) {

    private val binding = GlobalSearchControllerCardItemBinding.bind(view)

    init {
        // Call onMangaClickListener when item is pressed.
        itemView.setOnClickListener {
            val item = adapter.getItem(bindingAdapterPosition)
            if (item != null) {
                adapter.mangaClickListener.onMangaClick(item.manga)
            }
        }
        itemView.setOnLongClickListener {
            val item = adapter.getItem(bindingAdapterPosition)
            if (item != null) {
                adapter.mangaClickListener.onMangaLongClick(item.manga)
            }
            true
        }
    }

    fun bind(manga: Manga) {
        binding.card.clipToOutline = true

        binding.title.text = manga.title

        // set fav title color // Set alpha of thumbnail.
        if (manga.favorite) {
            binding.cover.alpha = 0.3f
            binding.title.setTextColor(Color.parseColor("#3399ff"))
        } else {
            binding.cover.alpha = 1.0f
            binding.title.setTextColor(Color.WHITE)
        }

        setImage(manga)
    }

    fun setImage(manga: Manga) {
        GlideApp.with(itemView.context).clear(binding.cover)
        if (!manga.thumbnail_url.isNullOrEmpty()) {
            GlideApp.with(itemView.context)
                .load(manga.toMangaThumbnail())
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .centerCrop()
                .skipMemoryCache(true)
                .placeholder(android.R.color.transparent)
                .into(StateImageViewTarget(binding.cover, binding.progress))
        }
    }
}
