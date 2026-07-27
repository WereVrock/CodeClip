package wv.codeclip.protocol.ui;

import wv.codeclip.protocol.model.Command;

/** Represents one command awaiting accept/reject decision in the review dialog. */
public final class PendingChange {
    public final Command command;
    public boolean accepted = true; // default to accepted; user can toggle off

    public PendingChange(Command command) {
        this.command = command;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(command.getType()).append(" — ").append(command.getId());
        if (command.getTargetId() != null) {
            sb.append(" (target: ").append(command.getTargetId()).append(")");
        }
        return sb.toString();
    }
}