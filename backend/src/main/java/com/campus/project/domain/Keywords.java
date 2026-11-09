package com.campus.project.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record Keywords(List<String> ro, List<String> en) {

    public List<String> all() {
        List<String> combined = new ArrayList<>();
        if (ro != null) {
            combined.addAll(ro);
        }
        if (en != null) {
            combined.addAll(en);
        }
        return Collections.unmodifiableList(combined);
    }
}
