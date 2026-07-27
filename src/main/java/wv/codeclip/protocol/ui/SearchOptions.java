package wv.codeclip.protocol.ui;

public final class SearchOptions {
    public boolean matchFileName = true;
    public boolean matchId = true;
    public boolean matchContent = true;

    public boolean anySelected() {
        return matchFileName || matchId || matchContent;
    }
}