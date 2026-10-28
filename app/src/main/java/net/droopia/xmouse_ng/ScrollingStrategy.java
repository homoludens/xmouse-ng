package net.droopia.xmouse_ng;

public interface ScrollingStrategy {

	boolean performScrolling(final int x, final int y, final CoolDragAndDropGridView view);

}
