package net.droopia.xmouse_ng;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;

import java.util.List;

public class HostListItemAdapter extends ArrayAdapter<HostItem> {

    private MyInterface myInterface;
    ListEditActivity.ActivityType type;
    private final class HostItemViewHolder {

        public TextView alias;
        public TextView desc;
        public TextView dbId;
        public MaterialButton edit;


    }
    private LayoutInflater mLayoutInflater = null;

    public HostListItemAdapter(Context context, List<HostItem> plugins, MyInterface a, ListEditActivity.ActivityType t) {
        super(context, R.layout.host_list_item, plugins);
        myInterface = a;
        mLayoutInflater = LayoutInflater.from(context);
        type= t;
    }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {

        final HostItemViewHolder itemViewHolder;

        if (convertView == null) {
            int rowLayout = type == ListEditActivity.ActivityType.type_script
                    ? R.layout.script_list_item : R.layout.host_list_item;
            convertView = mLayoutInflater.inflate(rowLayout, parent, false);

            itemViewHolder = new HostItemViewHolder();

            itemViewHolder.alias = (TextView) convertView.findViewById(R.id.hostAlias);
            itemViewHolder.desc = (TextView) convertView.findViewById(R.id.hostDesc);
            itemViewHolder.dbId = (TextView) convertView.findViewById(R.id.hostDBID);
            if (type == ListEditActivity.ActivityType.type_script) {
                itemViewHolder.edit = convertView.findViewById(R.id.script_manager_edit);
            }

            convertView.setTag(itemViewHolder);

        } else {

            itemViewHolder = (HostItemViewHolder) convertView.getTag();
        }

        final HostItem item = getItem(position);

        //SpanVariableGridView.LayoutParams lp = new SpanVariableGridView.LayoutParams(convertView.getLayoutParams());
        //lp.span = item.getmSpans();
        //convertView.setLayoutParams(lp);

        itemViewHolder.alias.setText(item.getAlias());
        if(type== ListEditActivity.ActivityType.type_host) {
            itemViewHolder.desc.setText(item.getUsername() + "@" + item.getIP() + ":" + item.getPort());
            itemViewHolder.dbId.setText(String.valueOf(item.getDbId()));
        }else if(type== ListEditActivity.ActivityType.type_script){
            itemViewHolder.alias.setMaxLines(2);
            itemViewHolder.alias.setEllipsize(android.text.TextUtils.TruncateAt.END);
            itemViewHolder.alias.setContentDescription(getContext().getString(R.string.ng_script_named, item.getAlias()));
            itemViewHolder.desc.setText(R.string.ng_script_ssh_label);
            itemViewHolder.dbId.setText(item.getUsername());
            String editDescription = getContext().getString(R.string.ng_edit_named_script, item.getAlias());
            itemViewHolder.edit.setContentDescription(editDescription);
            itemViewHolder.edit.setTooltipText(editDescription);
            itemViewHolder.edit.setOnClickListener(view -> myInterface.performCallback2(position));
            convertView.setContentDescription(editDescription);
        }

        /*if(MainActivity.setting_keyboard_show_details){
            itemViewHolder.itemCommand.setText(item.getmCommand());
            itemViewHolder.itemCommand.setSelected(true);
        }*/


        convertView.setOnClickListener(new View.OnClickListener(){

            @Override
            public void onClick(View v) {

                //myInterface.performCallback2(item.getDbId());
                myInterface.performCallback2(position);

                //Toast.makeText(mContext, "do edit or delete", Toast.LENGTH_SHORT).show();
            }


        });


        return convertView;
    }
}
