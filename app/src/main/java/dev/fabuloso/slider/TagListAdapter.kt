package dev.fabuloso.slider

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.TextView

class TagListAdapter(
    context: Context,
    private val records: List<StoredTagRecord>,
    private val onSave: (StoredTagRecord) -> Unit,
    private val onDelete: (StoredTagRecord) -> Unit,
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)

    override fun getCount(): Int = records.size

    override fun getItem(position: Int): StoredTagRecord = records[position]

    override fun getItemId(position: Int): Long = records[position].uidHex.hashCode().toLong()

    override fun hasStableIds(): Boolean = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view: View
        val holder: ViewHolder
        if (convertView == null) {
            view = inflater.inflate(R.layout.item_tag, parent, false)
            holder = ViewHolder(
                uid = view.findViewById(R.id.txtUid),
                data = view.findViewById(R.id.txtData),
                save = view.findViewById(R.id.btnSave),
                delete = view.findViewById(R.id.btnDelete),
            )
            view.tag = holder
        } else {
            view = convertView
            holder = view.tag as ViewHolder
        }

        val record = getItem(position)
        holder.uid.text = record.uid.reversedArray().toHexCompact().lowercase()
        holder.data.text = record.data.toHexCompact().lowercase()
        holder.save.setOnClickListener { onSave(record) }
        holder.delete.setOnClickListener { onDelete(record) }
        return view
    }

    private data class ViewHolder(
        val uid: TextView,
        val data: TextView,
        val save: ImageButton,
        val delete: ImageButton,
    )
}
