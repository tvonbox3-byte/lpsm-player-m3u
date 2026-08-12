package com.lpsm.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.lpsm.player.R
import com.lpsm.player.data.SecureStore
import com.lpsm.player.model.ContentType
import com.lpsm.player.model.MediaEntry

class MediaAdapter(
    private val store: SecureStore,
    private val open: (MediaEntry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_LIVE = 1
        private const val TYPE_POSTER = 2
    }

    private var data = listOf<MediaEntry>()
    private var epg = emptyMap<String, String>()

    fun submit(
        items: List<MediaEntry>,
        guide: Map<String, String>
    ) {
        data = items
        epg = guide
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int {
        return data.size
    }

    override fun getItemViewType(position: Int): Int {
        return if (data[position].type == ContentType.LIVE) {
            TYPE_LIVE
        } else {
            TYPE_POSTER
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {

        val inflater = LayoutInflater.from(parent.context)

        return if (viewType == TYPE_LIVE) {

            val view = inflater.inflate(
                R.layout.item_media,
                parent,
                false
            )

            LiveHolder(view)

        } else {

            val view = inflater.inflate(
                R.layout.item_media_poster,
                parent,
                false
            )

            PosterHolder(view)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int
    ) {

        val item = data[position]

        when (holder) {

            is LiveHolder -> {
                bindLive(
                    holder,
                    item
                )
            }

            is PosterHolder -> {
                bindPoster(
                    holder,
                    item
                )
            }
        }
    }

    private fun bindLive(
        holder: LiveHolder,
        item: MediaEntry
    ) {

        holder.title.text = item.name

        holder.subtitle.text =
            epg[item.tvgId]?.let { program ->

                "${item.group} · Agora: $program"

            } ?: item.group

        holder.star.text =
            if (store.isFavorite(item.url)) {
                "★ Favorito"
            } else {
                "☆ Favoritar"
            }

        holder.itemView.isFocusable = true
        holder.itemView.isClickable = true

        holder.itemView.setOnClickListener {

            open(item)
        }

        holder.itemView.setOnLongClickListener {

            store.toggleFavorite(
                item.url
            )

            val currentPosition =
                holder.bindingAdapterPosition

            if (
                currentPosition !=
                RecyclerView.NO_POSITION
            ) {
                notifyItemChanged(
                    currentPosition
                )
            }

            true
        }

        holder.itemView.setOnFocusChangeListener {
                view,
                hasFocus ->

            if (hasFocus) {

                view.scaleX = 1.03f
                view.scaleY = 1.03f

            } else {

                view.scaleX = 1.0f
                view.scaleY = 1.0f
            }
        }
    }

    private fun bindPoster(
        holder: PosterHolder,
        item: MediaEntry
    ) {

        holder.title.text =
            item.name

        holder.star.text =
            if (store.isFavorite(item.url)) {
                "★"
            } else {
                "☆"
            }

        /*
         * CAPA DO FILME OU SÉRIE
         */
        if (item.logo.isNotBlank()) {

            holder.poster.load(
                item.logo
            )

        } else {

            holder.poster.setImageResource(
                android.R.drawable.ic_menu_gallery
            )
        }

        holder.poster.contentDescription =
            "Capa de ${item.name}"

        holder.itemView.isFocusable = true
        holder.itemView.isClickable = true

        holder.itemView.setOnClickListener {

            open(item)
        }

        holder.itemView.setOnLongClickListener {

            store.toggleFavorite(
                item.url
            )

            val currentPosition =
                holder.bindingAdapterPosition

            if (
                currentPosition !=
                RecyclerView.NO_POSITION
            ) {
                notifyItemChanged(
                    currentPosition
                )
            }

            true
        }

        holder.itemView.setOnFocusChangeListener {
                view,
                hasFocus ->

            if (hasFocus) {

                view.scaleX = 1.06f
                view.scaleY = 1.06f
                view.translationZ = 10f

            } else {

                view.scaleX = 1.0f
                view.scaleY = 1.0f
                view.translationZ = 0f
            }
        }
    }

    class LiveHolder(
        view: View
    ) : RecyclerView.ViewHolder(view) {

        val title: TextView =
            view.findViewById(
                R.id.title
            )

        val subtitle: TextView =
            view.findViewById(
                R.id.subtitle
            )

        val star: TextView =
            view.findViewById(
                R.id.star
            )
    }

    class PosterHolder(
        view: View
    ) : RecyclerView.ViewHolder(view) {

        val poster: ImageView =
            view.findViewById(
                R.id.poster
            )

        val title: TextView =
            view.findViewById(
                R.id.posterTitle
            )

        val star: TextView =
            view.findViewById(
                R.id.posterStar
            )
    }
}
