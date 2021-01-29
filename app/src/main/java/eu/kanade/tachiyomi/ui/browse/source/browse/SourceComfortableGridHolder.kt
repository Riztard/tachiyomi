package eu.kanade.tachiyomi.ui.browse.source.browse

import android.graphics.Color
import android.view.View
import androidx.core.view.isVisible
import com.bumptech.glide.load.engine.DiskCacheStrategy
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.glide.GlideApp
import eu.kanade.tachiyomi.data.glide.toMangaThumbnail
import eu.kanade.tachiyomi.databinding.SourceComfortableGridItemBinding
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.widget.StateImageViewTarget
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.metadata.metadata.base.RaisedSearchMetadata

/**
 * Class used to hold the displayed data of a manga in the catalogue, like the cover or the title.
 * All the elements from the layout file "item_source_grid" are available in this class.
 *
 * @param view the inflated view for this holder.
 * @param adapter the adapter handling this holder.
 * @constructor creates a new catalogue holder.
 */
class SourceComfortableGridHolder(private val view: View, private val adapter: FlexibleAdapter<*> /* SY --> */, private val hasTitle: Boolean /* SY <-- */) :
    SourceHolder<SourceComfortableGridItemBinding>(view, adapter) {

    override val binding = SourceComfortableGridItemBinding.bind(view)

    /**
     * Method called from [CatalogueAdapter.onBindViewHolder]. It updates the data for this
     * holder with the given manga.
     *
     * @param manga the manga to bind.
     */
    override fun onSetValues(manga: Manga) {
        // Set manga title
        binding.title.text = manga.title
        // SY -->
        binding.title.isVisible = hasTitle
        // SY <--

        // set fav title color // Set alpha of thumbnail.
        if (manga.favorite) {
            binding.thumbnail.alpha = 0.3f
            binding.title.setTextColor(itemView.context.getResourceColor(R.attr.colorAccent))
        } else {
            binding.thumbnail.alpha = 1.0f
            binding.title.setTextColor(Color.WHITE)
        }

        setImage(manga)
    }

    // SY -->
    override fun onSetMetadataValues(manga: Manga, metadata: RaisedSearchMetadata) {
        if (metadata is MangaDexSearchMetadata) {
            metadata.follow_status?.let {
                binding.localText.text = itemView.context.resources.getStringArray(R.array.md_follows_options).asList()[it]
                binding.localText.isVisible = true
            }
        }
    }
    // SY <--

    override fun setImage(manga: Manga) {
        // For rounded corners
        binding.card.clipToOutline = true

        GlideApp.with(view.context).clear(binding.thumbnail)
        if (!manga.thumbnail_url.isNullOrEmpty()) {
            GlideApp.with(view.context)
                .load(manga.toMangaThumbnail())
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .centerCrop()
                .placeholder(android.R.color.transparent)
                .into(StateImageViewTarget(binding.thumbnail, binding.progress))
        }
    }
}
