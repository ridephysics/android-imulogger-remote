package com.ridephysics.imulogger_remote;

import android.graphics.Color
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.*

class HistoryAdapter(private val history: ArrayList<HistoryItem> = ArrayList()) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    data class HistoryItem(val text: String, val color: Int = Color.WHITE)

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        var mTextView = v.findViewById<TextView>(R.id.text)!!

    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {

        // Create View
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.history_row, parent, false)

        return ViewHolder(v)
    }

    fun add(data: HistoryItem) {
        history.add(data)
        this.notifyDataSetChanged()
    }

    fun add(text: String, color:Int = Color.WHITE) {
        this.add(HistoryItem(text, color))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = history[position]
        holder.mTextView.text = item.text
        holder.mTextView.setTextColor(item.color)
    }

    override fun getItemCount(): Int {
        return history.size
    }


}