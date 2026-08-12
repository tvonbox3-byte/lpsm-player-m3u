package com.lpsm.player.ui
import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lpsm.player.R
import com.lpsm.player.data.SecureStore
import com.lpsm.player.model.MediaEntry
class MediaAdapter(private val store:SecureStore,private val open:(MediaEntry)->Unit):RecyclerView.Adapter<MediaAdapter.H>(){
 private var data=listOf<MediaEntry>();private var epg=emptyMap<String,String>()
 fun submit(items:List<MediaEntry>,guide:Map<String,String>){data=items;epg=guide;notifyDataSetChanged()}
 override fun onCreateViewHolder(p:ViewGroup,t:Int)=H(LayoutInflater.from(p.context).inflate(R.layout.item_media,p,false))
 override fun getItemCount()=data.size
 override fun onBindViewHolder(h:H,i:Int){val x=data[i];h.title.text=x.name;h.subtitle.text=epg[x.tvgId]?.let{"${x.group} · Agora: $it"}?:x.group;h.star.text=if(store.isFavorite(x.url))"★ Favorito" else "☆ Favoritar";h.itemView.setOnClickListener{open(x)};h.itemView.setOnLongClickListener{store.toggleFavorite(x.url);notifyItemChanged(i);true}}
 class H(v:View):RecyclerView.ViewHolder(v){val title:TextView=v.findViewById(R.id.title);val subtitle:TextView=v.findViewById(R.id.subtitle);val star:TextView=v.findViewById(R.id.star)}
}
