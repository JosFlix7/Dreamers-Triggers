package dev.dreamers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;

final class NameColor {
    private NameColor() {
    }

    static Component named(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    static Component paint(Component component, TextColor color) {
        List<Component> children = component.children();
        Component painted = children.isEmpty()
                ? component
                : component.children(children.stream().map(child -> paint(child, color)).toList());
        return color.equals(painted.color()) ? painted : painted.color(color);
    }
}
