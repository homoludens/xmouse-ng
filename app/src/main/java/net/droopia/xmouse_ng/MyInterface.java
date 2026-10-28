package net.droopia.xmouse_ng;

public interface MyInterface {
	void performCallback();
	void performCallback2(Integer i);
	default void showRecentCommand(String command) { }

}
