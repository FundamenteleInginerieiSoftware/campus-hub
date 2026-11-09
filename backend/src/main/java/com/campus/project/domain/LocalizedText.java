package com.campus.project.domain;

public record LocalizedText(String ro, String en) {

    public String in(Lang lang) {
        return lang == Lang.RO ? ro : en;
    }
}
