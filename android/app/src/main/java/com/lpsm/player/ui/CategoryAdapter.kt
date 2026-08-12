package com.lpsm.player.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lpsm.player.R

data class CategoryRow(
    val name: String,
    val count: Int
)

class CategoryAdapter(
    private val select: (String) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.Holder>() {

    private var rows =
        emptyList<CategoryRow>()

    private var selected =
        "Tudo"

    fun submit(
        items: List<CategoryRow>,
        selectedName: String
    ) {
        if (
            rows == items &&
            selected == selectedName
        ) {
            return
        }

        rows = items
        selected = selectedName

        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): Holder {

        val view =
            LayoutInflater
                .from(parent.context)
                .inflate(
                    R.layout.item_category,
                    parent,
                    false
                )

        return Holder(view)
    }

    override fun getItemCount(): Int {
        return rows.size
    }

    override fun onBindViewHolder(
        holder: Holder,
        position: Int
    ) {

        val row =
            rows[position]

        holder.name.text =
            row.name

        holder.count.text =
            row.count.toString()

        /*
         * AMARELO:
         * categoria/temporada selecionada.
         */
        holder.itemView.isSelected =
            row.name == selected

        holder.itemView.isFocusable =
            true

        holder.itemView
            .isFocusableInTouchMode =
            false

        holder.itemView.isClickable =
            true

        /*
         * Mouse / touch.
         */
        holder.itemView
            .setOnClickListener {

                select(
                    row.name
                )
            }

        /*
         * CONTROLE REMOTO / TECLADO.
         */
        holder.itemView
            .setOnKeyListener {
                    view,
                    keyCode,
                    event ->

                if (
                    event.action !=
                    KeyEvent.ACTION_DOWN
                ) {
                    return@setOnKeyListener false
                }

                when (keyCode) {

                    /*
                     * OK / ENTER:
                     *
                     * seleciona a categoria
                     * ou a temporada.
                     */
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {

                        select(
                            row.name
                        )

                        true
                    }

                    /*
                     * SETA DIREITA:
                     *
                     * seleciona a temporada
                     * onde o controle está
                     * e entra nos episódios.
                     *
                     * Também funciona em
                     * categorias de canais.
                     */
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {

                        select(
                            row.name
                        )

                        moveToContent(
                            view
                        )

                        true
                    }

                    /*
                     * CIMA/BAIXO continuam
                     * usando a navegação
                     * normal do Android TV.
                     */
                    else -> false
                }
            }

        /*
         * VERDE:
         * onde o controle está agora.
         *
         * O drawable category_background
         * já possui state_focused.
         */
        holder.itemView
            .setOnFocusChangeListener {
                    view,
                    hasFocus ->

                if (hasFocus) {

                    view.scaleX =
                        1.02f

                    view.scaleY =
                        1.02f

                    view.translationZ =
                        8f

                } else {

                    view.scaleX =
                        1f

                    view.scaleY =
                        1f

                    view.translationZ =
                        0f
                }
            }
    }

    /*
     * Leva o controle da coluna
     * de categorias/temporadas
     * para canais/capas/episódios.
     */
    private fun moveToContent(
        source: View
    ) {

        val list =
            source.rootView
                .findViewById<
                    RecyclerView
                    >(
                    R.id.list
                )

        list.post {

            /*
             * Preferimos o primeiro
             * item visível.
             */
            val child =
                list.getChildAt(0)

            if (
                child != null &&
                child.isFocusable
            ) {

                child.requestFocus()

            } else {

                list.requestFocus()
            }
        }
    }

    class Holder(
        view: View
    ) : RecyclerView.ViewHolder(
        view
    ) {

        val name: TextView =
            view.findViewById(
                R.id.categoryName
            )

        val count: TextView =
            view.findViewById(
                R.id.categoryCount
            )
    }
}
