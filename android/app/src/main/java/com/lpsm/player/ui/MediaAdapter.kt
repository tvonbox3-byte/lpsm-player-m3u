package com.lpsm.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

import androidx.recyclerview.widget.RecyclerView

import coil3.load
import coil3.request.error
import coil3.request.fallback
import coil3.request.placeholder

import com.lpsm.player.R
import com.lpsm.player.data.SecureStore
import com.lpsm.player.model.ContentType
import com.lpsm.player.model.MediaEntry


class MediaAdapter(
    private val store: SecureStore,
    private val open: (MediaEntry) -> Unit,
    private val focused: (MediaEntry?) -> Unit = {},
    private val favoriteChanged: (MediaEntry, Boolean) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    init {
        setHasStableIds(true)
    }


    companion object {

        private const val TYPE_ROW =
            1

        private const val TYPE_POSTER =
            2
    }


    private var data =
        listOf<MediaEntry>()


    private var epg =
        emptyMap<String, String>()


    private var posterMode =
        true


    /*
     * Guarda capas encontradas das séries.
     *
     * Quando entramos nos episódios,
     * algumas listas não mandam uma imagem
     * diferente para cada episódio.
     *
     * Nesse caso usamos a capa que já
     * encontramos para a própria série.
     */
    private val rememberedSeriesLogos =
        mutableMapOf<String, String>()



    /*
     * =====================================================
     * RECEBER ITENS
     * =====================================================
     */

    fun submit(
        items: List<MediaEntry>,
        guide: Map<String, String>,
        usePosters: Boolean = true
    ) {

        data =
            items


        epg =
            guide


        posterMode =
            usePosters


        /*
         * Memoriza todas as capas disponíveis.
         */
        if (items.firstOrNull()?.type == ContentType.SERIES) {
            items.forEach { item ->
                if (item.logo.isNotBlank()) {
                    rememberedSeriesLogos[
                        seriesIdentity(item)
                    ] = item.logo
                }
            }
        }


        notifyDataSetChanged()
    }



    override fun getItemCount():
        Int {

        return data.size
    }

    override fun getItemId(position: Int): Long =
        data[position].url.hashCode().toLong()



    /*
     * =====================================================
     * TIPO DO CARD
     * =====================================================
     */

    override fun getItemViewType(
        position: Int
    ): Int {

        val item =
            data[position]


        return if (

            item.type ==
                ContentType.LIVE ||

            !posterMode

        ) {

            TYPE_ROW

        } else {

            TYPE_POSTER
        }
    }



    /*
     * =====================================================
     * CRIAR CARD
     * =====================================================
     */

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {

        val inflater =
            LayoutInflater
                .from(
                    parent.context
                )


        return if (
            viewType ==
            TYPE_ROW
        ) {

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



    /*
     * =====================================================
     * PREENCHER CARD
     * =====================================================
     */

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int
    ) {

        val item =
            data[position]


        when (
            holder
        ) {

            is RowHolder -> {

                bindRow(
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



    /*
     * =====================================================
     * CARD HORIZONTAL
     *
     * TV AO VIVO E EPISÓDIOS
     * =====================================================
     */

    private fun bindRow(
        holder: RowHolder,
        item: MediaEntry
    ) {

        /*
         * TÍTULO
         *
         * Exemplo:
         *
         * Silo S01E01
         *
         * ou:
         *
         * Silo S01E01 (L)
         */
        holder.title.text =
            buildRowTitle(
                item
            )


        /*
         * SUBTÍTULO
         */
        holder.subtitle.text =
            when (
                item.type
            ) {

                ContentType.LIVE -> {

                    epg[
                        item.tvgId
                    ]
                        ?.let {
                            now ->

                            "${item.group} • Agora: $now"
                        }

                        ?: item.group
                }


                ContentType.SERIES -> {

                    buildEpisodeSubtitle(
                        item
                    )
                }


                ContentType.VOD -> {

                    item.group
                }
            }



        /*
         * FAVORITO
         */
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



        /*
         * IMAGEM
         */
        val imageUrl =
            imageFor(
                item
            )


        val rowFallback =
            if (isRadioEntry(item)) {
                R.drawable.ic_radio_station
            } else {
                android.R.drawable.ic_menu_gallery
            }

        /*
         * Carregar inclusive null cancela a requisicao antiga do ViewHolder.
         * Sem isso uma logo de canal podia terminar o download depois que o
         * cartao ja havia sido reciclado para uma radio.
         */
        holder.mediaImage
            .load(
                imageUrl.takeIf { it.isNotBlank() }
            ) {
                placeholder(rowFallback)
                fallback(rowFallback)
                error(rowFallback)
            }


        holder.mediaImage
            .contentDescription =
            when (
                item.type
            ) {

                ContentType.SERIES ->
                    "Imagem do episódio ${item.name}"

                ContentType.LIVE ->
                    "Logo de ${item.name}"

                else ->
                    "Imagem de ${item.name}"
            }



        /*
         * NÚMERO DO EPISÓDIO
         *
         * Aparece em cima da imagem,
         * igual ao modelo enviado.
         */
        if (

            item.type ==
                ContentType.SERIES &&

            item.episode !=
                null

        ) {

            holder.episodeBadge
                .visibility =
                View.VISIBLE


            holder.episodeBadge
                .text =
                item.episode
                    .toString()

        } else {

            holder.episodeBadge
                .visibility =
                View.GONE


            holder.episodeBadge
                .text =
                ""
        }



        configureItem(
            holder.itemView,
            item,
            1.035f
        )



        /*
         * SEGURAR OK / CLICK
         * FAVORITA O CONTEÚDO.
         */
        holder.itemView
            .setOnLongClickListener {
                toggleFavorite(
                    holder,
                    item
                )
                true
            }

        holder.star
            .apply {
                isFocusable = true
                isFocusableInTouchMode = false
                contentDescription = "Favoritar ${item.name}"
            }

        holder.star
            .setOnClickListener {
                toggleFavorite(
                    holder,
                    item
                )
            }
    }



    /*
     * =====================================================
     * CAPA DE FILME / SÉRIE
     * =====================================================
     */

    private fun bindPoster(
        holder: PosterHolder,
        item: MediaEntry
    ) {

        /*
         * Mostra (L) também na capa
         * quando for conteúdo legendado.
         */
        holder.title.text =
            addLegendTag(
                item.name,
                item
            )



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



        val imageUrl =
            imageFor(
                item
            )


        holder.poster
            .load(
                imageUrl.takeIf { it.isNotBlank() }
            ) {
                placeholder(android.R.drawable.ic_menu_gallery)
                fallback(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_menu_gallery)
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
                toggleFavorite(
                    holder,
                    item
                )
                true
            }

        holder.star
            .apply {
                isFocusable = true
                isFocusableInTouchMode = false
                contentDescription = "Favoritar ${item.name}"
            }

        holder.star
            .setOnClickListener {
                toggleFavorite(
                    holder,
                    item
                )
            }
    }



    private fun toggleFavorite(
        holder: RecyclerView.ViewHolder,
        item: MediaEntry
    ) {

        /*
         * BUILD 42 - TV BOX / FAVORITOS
         *
         * Algumas boxes enviam o OK/clique enquanto o RecyclerView ainda esta
         * calculando layout. A gravacao do favorito acontecia, mas uma atualizacao
         * imediata do adapter/tela podia derrubar a Activity. Persistimos primeiro
         * e postamos apenas a atualizacao daquele item para o proximo ciclo da UI.
         */
        val added =
            try {
                store.toggleFavorite(
                    item.url
                )
            } catch (_: Throwable) {
                return
            }

        holder.itemView.post {
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
        }

        favoriteChanged(
            item,
            added
        )
    }



    /*
     * =====================================================
     * CLIQUE E FOCO DO CONTROLE
     * =====================================================
     */

    private fun configureItem(
        view: View,
        item: MediaEntry,
        focusScale: Float
    ) {

        view.isFocusable =
            true


        view.isFocusableInTouchMode =
            false


        view.isClickable =
            true



        /*
         * OK / ENTER
         */
        view.setOnClickListener {

            open(
                item
            )
        }



        /*
         * FOCO DO CONTROLE
         */
        view.setOnFocusChangeListener {
                itemView,
                hasFocus ->


            if (
                hasFocus
            ) {

                itemView.scaleX =
                    focusScale


                itemView.scaleY =
                    focusScale


                itemView.translationZ =
                    12f


                focused(
                    item
                )

            } else {

                itemView.scaleX =
                    1.0f


                itemView.scaleY =
                    1.0f


                itemView.translationZ =
                    0f


                focused(
                    null
                )
            }
        }
    }



    /*
     * =====================================================
     * TÍTULO DO EPISÓDIO
     * =====================================================
     */

    private fun buildRowTitle(
        item: MediaEntry
    ): String {

        /*
         * TV / FILME
         */
        if (
            item.type !=
            ContentType.SERIES
        ) {

            return addLegendTag(
                item.name,
                item
            )
        }


        /*
         * Série sem número identificado.
         */
        if (

            item.season ==
                null ||

            item.episode ==
                null

        ) {

            return addLegendTag(
                item.name,
                item
            )
        }



        val seriesName =
            cleanSeriesName(
                item
            )


        val code =
            episodeCode(
                item
            )


        val title =
            if (
                code.isBlank()
            ) {

                seriesName

            } else {

                "$seriesName $code"
            }


        return addLegendTag(
            title,
            item
        )
    }



    /*
     * =====================================================
     * S01E01
     * =====================================================
     */

    private fun episodeCode(
        item: MediaEntry
    ): String {

        val season =
            item.season
                ?: return ""


        val episode =
            item.episode
                ?: return ""


        return String.format(
            "S%02dE%02d",
            season,
            episode
        )
    }



    /*
     * =====================================================
     * NOME LIMPO DA SÉRIE
     * =====================================================
     */

    private fun cleanSeriesName(
        item: MediaEntry
    ): String {

        /*
         * O parser já entrega seriesName
         * quando consegue identificar.
         */
        val parsed =
            item.seriesName
                .trim()


        if (
            parsed.isNotBlank()
        ) {

            return cleanLegendMarkers(
                parsed
            )
        }



        var cleaned =
            item.name



        /*
         * Remove:
         *
         * S01E01
         * T01E01
         */
        cleaned =
            cleaned.replace(

                Regex(
                    """(?i)\b[ST]\d{1,2}\s*E\d{1,3}\b.*$"""
                ),

                ""
            )



        /*
         * Remove:
         *
         * 1x01
         */
        cleaned =
            cleaned.replace(

                Regex(
                    """(?i)\b\d{1,2}\s*[xX]\s*\d{1,3}\b.*$"""
                ),

                ""
            )



        cleaned =
            cleaned.trim(
                ' ',
                '-',
                '–',
                '—',
                '|',
                ':'
            )



        cleaned =
            cleanLegendMarkers(
                cleaned
            )



        return cleaned
            .ifBlank {

                cleanLegendMarkers(
                    item.name
                )
            }
    }



    /*
     * =====================================================
     * SUBTÍTULO DO EPISÓDIO
     * =====================================================
     */

    private fun buildEpisodeSubtitle(
        item: MediaEntry
    ): String {

        val parts =
            mutableListOf<String>()



        item.season
            ?.let {

                parts +=
                    "Temporada $it"
            }



        item.episode
            ?.let {

                parts +=
                    "Episódio $it"
            }



        /*
         * Se o grupo tiver uma informação útil
         * como Netflix, Amazon, Pedidos etc.,
         * mostra junto.
         */
        val group =
            item.group
                .trim()


        if (

            group.isNotBlank() &&

            !group.equals(
                "Outros",
                true
            ) &&

            !group.equals(
                item.seriesName,
                true
            )

        ) {

            parts +=
                group
        }



        if (
            parts.isEmpty()
        ) {

            parts +=
                item.group
        }



        return parts
            .joinToString(
                " • "
            )
    }



    /*
     * =====================================================
     * DETECTAR LEGENDADO
     * =====================================================
     */

    private fun isLegendado(
        item: MediaEntry
    ): Boolean {

        val combined =
            buildString {

                append(
                    item.name
                )

                append(
                    ' '
                )

                append(
                    item.seriesName
                )

                append(
                    ' '
                )

                append(
                    item.group
                )
            }



        /*
         * [L]
         */
        if (

            Regex(
                """(?i)\[\s*L\s*]"""
            )
                .containsMatchIn(
                    combined
                )

        ) {

            return true
        }



        /*
         * (L)
         */
        if (

            Regex(
                """(?i)\(\s*L\s*\)"""
            )
                .containsMatchIn(
                    combined
                )

        ) {

            return true
        }



        /*
         * LEGENDADO / LEGENDADA
         * LEGENDADOS / LEGENDADAS
         */
        if (

            Regex(
                """(?i)\blegendad[oa]s?\b"""
            )
                .containsMatchIn(
                    combined
                )

        ) {

            return true
        }



        return false
    }



    /*
     * =====================================================
     * ADICIONAR (L)
     * =====================================================
     */

    private fun addLegendTag(
        title: String,
        item: MediaEntry
    ): String {

        val clean =
            cleanLegendMarkers(
                title
            )


        return if (
            isLegendado(
                item
            )
        ) {

            "$clean (L)"

        } else {

            clean
        }
    }



    /*
     * =====================================================
     * REMOVER [L] / (L)
     *
     * Depois adicionamos sempre no padrão:
     *
     * Nome (L)
     * =====================================================
     */

    private fun cleanLegendMarkers(
        value: String
    ): String {

        return value

            .replace(
                Regex(
                    """(?i)\[\s*L\s*]"""
                ),
                ""
            )

            .replace(
                Regex(
                    """(?i)\(\s*L\s*\)"""
                ),
                ""
            )

            .replace(
                Regex(
                    """\s+"""
                ),
                " "
            )

            .trim()
    }



    /*
     * =====================================================
     * IDENTIDADE DA SÉRIE
     * =====================================================
     */

    private fun seriesIdentity(
        item: MediaEntry
    ): String {

        return cleanSeriesName(
            item
        )
            .lowercase()
            .trim()
    }



    /*
     * =====================================================
     * IMAGEM
     * =====================================================
     */

    private fun imageFor(
        item: MediaEntry
    ): String {

        /*
         * Primeiro usa imagem do próprio item.
         */
        if (
            item.logo.isNotBlank()
        ) {

            return item.logo
        }



        /*
         * Para episódio sem imagem,
         * tenta usar a capa que já foi
         * encontrada para a série.
         */
        if (
            item.type ==
            ContentType.SERIES
        ) {

            return rememberedSeriesLogos[
                seriesIdentity(
                    item
                )
            ]
                .orEmpty()
        }



        return ""
    }

    private fun isRadioEntry(
        item: MediaEntry
    ): Boolean =
        item.tvgId.startsWith("radio:")



    /*
     * =====================================================
     * HOLDER HORIZONTAL
     * =====================================================
     */

    class RowHolder(
        view: View
    ) : RecyclerView.ViewHolder(
        view
    ) {

        val mediaImage:
            ImageView =
            view.findViewById(
                R.id.mediaImage
            )


        val episodeBadge:
            TextView =
            view.findViewById(
                R.id.episodeBadge
            )


        val title:
            TextView =
            view.findViewById(
                R.id.title
            )


        val subtitle:
            TextView =
            view.findViewById(
                R.id.subtitle
            )


        val star:
            TextView =
            view.findViewById(
                R.id.star
            )
    }



    /*
     * =====================================================
     * HOLDER DAS CAPAS
     * =====================================================
     */

    class PosterHolder(
        view: View
    ) : RecyclerView.ViewHolder(
        view
    ) {

        val poster:
            ImageView =
            view.findViewById(
                R.id.poster
            )


        val title:
            TextView =
            view.findViewById(
                R.id.posterTitle
            )


        val star:
            TextView =
            view.findViewById(
                R.id.posterStar
            )
    }
}
