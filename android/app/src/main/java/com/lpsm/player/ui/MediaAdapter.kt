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
    private val open: (MediaEntry) -> Unit,
    private val focused: (MediaEntry?) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_ROW = 1
        private const val TYPE_POSTER = 2
    }

    private var data = listOf<MediaEntry>()
    private var epg = emptyMap<String, String>()
    private var posterMode = true

    fun submit(
        items: List<MediaEntry>,
        guide: Map<String, String>,
        usePosters: Boolean = true
    ) {
        data = items
        epg = guide
        posterMode = usePosters
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int {
        return data.size
    }

    override fun getItemViewType(position: Int): Int {
        val item = data[position]

        return if (
            item.type == ContentType.LIVE ||
            !posterMode
        ) {
            TYPE_ROW
        } else {
            TYPE_POSTER
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {

        val inflater =
            LayoutInflater.from(parent.context)

        return if (viewType == TYPE_ROW) {

            RowHolder(
                inflater.inflate(
                    R.layout.item_media,
                    parent,
                    false
                )
            )

        } else {

            PosterHolder(
                inflater.inflate(
                    R.layout.item_media_poster,
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int
    ) {
        val item = data[position]

        when (holder) {
            is RowHolder ->
                bindRow(holder, item)

            is PosterHolder ->
                bindPoster(holder, item)
        }
    }

    private fun bindRow(
        holder: RowHolder,
        item: MediaEntry
    ) {

        holder.title.text =
            item.name

        holder.subtitle.text =
            when (item.type) {

                ContentType.LIVE -> {
                    epg[item.tvgId]
                        ?.let {
                            "${item.group} · Agora: $it"
                        }
                        ?: item.group
                }

                ContentType.SERIES -> {
                    buildEpisodeSubtitle(item)
                }

                ContentType.VOD -> {
                    item.group
                }
            }

        holder.star.text =
            if (
                store.isFavorite(
                    item.url
                )
            ) {
                "★ Favorito"
            } else {
                "☆ Favoritar"
            }

        configureItem(
            holder.itemView,
            item,
            1.035f
        )

        holder.itemView
            .setOnLongClickListener {

                store.toggleFavorite(
                    item.url
                )

                val position =
                    holder.bindingAdapterPosition

                if (
                    position !=
                    RecyclerView.NO_POSITION
                ) {
                    notifyItemChanged(
                        position
                    )
                }

                true
            }
    }

    private fun bindPoster(
        holder: PosterHolder,
        item: MediaEntry
    ) {

        holder.title.text =
            item.name

        holder.star.text =
            if (
                store.isFavorite(
                    item.url
                )
            ) {
                "★"
            } else {
                "☆"
            }

        if (
            item.logo.isNotBlank()
        ) {
            holder.poster.load(
                item.logo
            )
        } else {
            holder.poster
                .setImageResource(
                    android.R.drawable
                        .ic_menu_gallery
                )
        }

        holder.poster
            .contentDescription =
            "Capa de ${item.name}"

        configureItem(
            holder.itemView,
            item,
            1.075f
        )

        holder.itemView
            .setOnLongClickListener {

                store.toggleFavorite(
                    item.url
                )

                val position =
                    holder.bindingAdapterPosition

                if (
                    position !=
                    RecyclerView.NO_POSITION
                ) {
                    notifyItemChanged(
                        position
                    )
                }

                true
            }
    }

    private fun configureItem(
        view: View,
        item: MediaEntry,
        focusScale: Float
    ) {

        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.isClickable = true

        /*
         * OK / ENTER do controle remoto
         * executa o mesmo clique.
         */
        view.setOnClickListener {
            open(item)
        }

        /*
         * Quando o cliente navega pelas
         * setas do controle, MainActivity
         * recebe o item que ganhou foco.
         */
        view.setOnFocusChangeListener {
                itemView,
                hasFocus ->

            if (hasFocus) {

                itemView.scaleX =
                    focusScale

                itemView.scaleY =
                    focusScale

                itemView.translationZ =
                    12f

                focused(item)

            } else {

                itemView.scaleX =
                    1.0f

                itemView.scaleY =
                    1.0f

                itemView.translationZ =
                    0f

                focused(null)
            }
        }
    }

    private fun buildEpisodeSubtitle(
        item: MediaEntry
    ): String {

        val parts =
            mutableListOf<String>()

        item.season?.let {
            parts +=
                "Temporada $it"
        }

        item.episode?.let {
            parts +=
                "Episódio $it"
        }

        if (
            parts.isEmpty()
        ) {
            parts += item.group
        }

        return parts.joinToString(
            " • "
        )
    }

    class RowHolder(
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
