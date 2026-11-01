package com.jianjin.assistant.service.office;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ReviewDiffService {

    public record Change(String type, int originalLine, int revisedLine, String text) {}
    public record Result(int added, int removed, int unchanged, List<Change> changes) {}

    public Result compare(String original, String revised) {
        String[] left = lines(original);
        String[] right = lines(revised);
        int[][] lcs = new int[left.length + 1][right.length + 1];
        for (int i = left.length - 1; i >= 0; i--) {
            for (int j = right.length - 1; j >= 0; j--) {
                lcs[i][j] = left[i].equals(right[j]) ? lcs[i + 1][j + 1]
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Change> changes = new ArrayList<>();
        int i = 0, j = 0, added = 0, removed = 0, unchanged = 0;
        while (i < left.length || j < right.length) {
            if (i < left.length && j < right.length && left[i].equals(right[j])) {
                changes.add(new Change("unchanged", i + 1, j + 1, left[i]));
                i++; j++; unchanged++;
            } else if (j < right.length && (i == left.length || lcs[i][j + 1] >= lcs[i + 1][j])) {
                changes.add(new Change("added", 0, j + 1, right[j++]));
                added++;
            } else {
                changes.add(new Change("removed", i + 1, 0, left[i++]));
                removed++;
            }
        }
        return new Result(added, removed, unchanged, changes);
    }

    private static String[] lines(String value) {
        if (value == null || value.isEmpty()) return new String[0];
        return value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }
}
