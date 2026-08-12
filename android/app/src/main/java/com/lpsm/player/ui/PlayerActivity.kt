package com.lpsm.player.ui
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.lpsm.player.databinding.ActivityPlayerBinding
class PlayerActivity:AppCompatActivity(){private lateinit var b:ActivityPlayerBinding;private var player:ExoPlayer?=null
 override fun onCreate(s:Bundle?){super.onCreate(s);b=ActivityPlayerBinding.inflate(layoutInflater);setContentView(b.root);b.title.text=intent.getStringExtra("name")}
 override fun onStart(){super.onStart();val url=intent.getStringExtra("url")?:return;player=ExoPlayer.Builder(this).build().also{b.playerView.player=it;it.setMediaItem(MediaItem.fromUri(url));it.prepare();it.playWhenReady=true}}
 override fun onStop(){player?.release();player=null;super.onStop()}
}
