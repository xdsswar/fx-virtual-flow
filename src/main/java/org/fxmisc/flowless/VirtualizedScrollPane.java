package org.fxmisc.flowless;

import static javafx.scene.control.ScrollPane.ScrollBarPolicy.*;

import javafx.application.Platform;
import javafx.beans.DefaultProperty;
import javafx.beans.NamedArg;
import javafx.beans.Observable;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;

import org.reactfx.value.Val;
import org.reactfx.value.Var;

@DefaultProperty("content")
public class VirtualizedScrollPane<V extends Region & Virtualized> extends Region implements Virtualized {

    private static final PseudoClass CONTENT_FOCUSED = PseudoClass.getPseudoClass("content-focused");

    private final ScrollBar hbar;
    private final ScrollBar vbar;
    private final V content;
    private final ChangeListener<Boolean> contentFocusedListener;
    private final ChangeListener<Double> hbarValueListener;
    private ChangeListener<Double> hPosEstimateListener;
    private final ChangeListener<Double> vbarValueListener;
    private final ChangeListener<Double> vPosEstimateListener;

    private Var<Double> hbarValue;
    private Var<Double> vbarValue;
    private Var<Double> hPosEstimate;
    private Var<Double> vPosEstimate;

    /** The Policy for the Horizontal ScrollBar */
    private final Var<ScrollPane.ScrollBarPolicy> hbarPolicy;
    public final ScrollPane.ScrollBarPolicy getHbarPolicy() { return hbarPolicy.getValue(); }
    public final void setHbarPolicy(ScrollPane.ScrollBarPolicy value) { hbarPolicy.setValue(value); }
    public final Var<ScrollPane.ScrollBarPolicy> hbarPolicyProperty() { return hbarPolicy; }

    /** The Policy for the Vertical ScrollBar */
    private final Var<ScrollPane.ScrollBarPolicy> vbarPolicy;
    public final ScrollPane.ScrollBarPolicy getVbarPolicy() { return vbarPolicy.getValue(); }
    public final void setVbarPolicy(ScrollPane.ScrollBarPolicy value) { vbarPolicy.setValue(value); }
    public final Var<ScrollPane.ScrollBarPolicy> vbarPolicyProperty() { return vbarPolicy; }

    /**
     * Constructs a VirtualizedScrollPane with the given content and policies
     */
    public VirtualizedScrollPane(
        @NamedArg("content") V content,
        @NamedArg("hPolicy") ScrollPane.ScrollBarPolicy hPolicy,
        @NamedArg("vPolicy") ScrollPane.ScrollBarPolicy vPolicy
    ) {
        this.getStyleClass().add("virtualized-scroll-pane");
        this.content = content;

        // create scrollbars
        hbar = new ScrollBar();
        vbar = new ScrollBar();
        hbar.setOrientation(Orientation.HORIZONTAL);
        vbar.setOrientation(Orientation.VERTICAL);

        // scrollbar ranges
        hbar.setMin(0);
        vbar.setMin(0);
        hbar.maxProperty().bind(content.totalWidthEstimateProperty());
        vbar.maxProperty().bind(content.totalHeightEstimateProperty());

        // scrollbar increments
        setupUnitIncrement(hbar);
        setupUnitIncrement(vbar);
        hbar.blockIncrementProperty().bind(hbar.visibleAmountProperty());
        vbar.blockIncrementProperty().bind(vbar.visibleAmountProperty());

        // scrollbar positions
        hPosEstimate = Val.combine(
                    content.estimatedScrollXProperty(),
                    Val.map(content.layoutBoundsProperty(), Bounds::getWidth),
                    Val.map(content.paddingProperty(), p -> p.getLeft() + p.getRight()),
                    content.totalWidthEstimateProperty(),
                    VirtualizedScrollPane::offsetToScrollbarPosition)
                .asVar(this::setHPosition);
       vPosEstimate = Val.combine(
                    content.estimatedScrollYProperty(),
                    Val.map(content.layoutBoundsProperty(), Bounds::getHeight),
                    Val.map(content.paddingProperty(), p -> p.getTop() + p.getBottom()),
                    content.totalHeightEstimateProperty(),
                    VirtualizedScrollPane::offsetToScrollbarPosition)
                .orElseConst(0.0)
                .asVar(this::setVPosition);
        hbarValue = Var.doubleVar(hbar.valueProperty());
        vbarValue = Var.doubleVar(vbar.valueProperty());
        // The use of a pair of mirrored ChangeListener instead of a more natural bidirectional binding
        // here is a workaround following a change in JavaFX [1] which broke the behaviour of the scroll bar [2].
        // [1] https://bugs.openjdk.java.net/browse/JDK-8264770
        // [2] https://github.com/FXMisc/Flowless/issues/97
        hbarValueListener = (observable, oldValue, newValue) -> {
        	// Fix for update anomaly reported here https://github.com/FXMisc/RichTextFX/issues/1030
            hPosEstimate.removeListener(hPosEstimateListener);
            hPosEstimate.setValue(newValue);
            hPosEstimate.addListener(hPosEstimateListener);
        };
        hbarValue.addListener(hbarValueListener);
        hPosEstimateListener = (observable, oldValue, newValue) -> {
        	// Fix for update anomaly reported here https://github.com/FXMisc/RichTextFX/issues/1030
            hbarValue.removeListener(hbarValueListener);
            hbarValue.setValue(newValue);
            hbarValue.addListener(hbarValueListener);
        };
        hPosEstimate.addListener(hPosEstimateListener);
        vbarValueListener = (observable, oldValue, newValue) -> vPosEstimate.setValue(newValue);
        vbarValue.addListener(vbarValueListener);
        vPosEstimateListener = (observable, oldValue, newValue) -> vbarValue.setValue(newValue);
        vPosEstimate.addListener(vPosEstimateListener);

        // scrollbar visibility
        hbarPolicy = Var.newSimpleVar(hPolicy);
        vbarPolicy = Var.newSimpleVar(vPolicy);

        Val<Double> layoutWidth = Val.map(layoutBoundsProperty(), Bounds::getWidth);
        Val<Double> layoutHeight = Val.map(layoutBoundsProperty(), Bounds::getHeight);
        Val<Boolean> needsHBar0 = Val.combine(
                content.totalWidthEstimateProperty(),
                layoutWidth,
                (cw, lw) -> cw > lw);
        Val<Boolean> needsVBar0 = Val.combine(
                content.totalHeightEstimateProperty(),
                layoutHeight,
                (ch, lh) -> ch > lh);
        Val<Boolean> needsHBar = Val.combine(
                needsHBar0,
                needsVBar0,
                content.totalWidthEstimateProperty(),
                vbar.widthProperty(),
                layoutWidth,
                (needsH, needsV, cw, vbw, lw) -> needsH || needsV && cw + vbw.doubleValue() > lw);
        Val<Boolean> needsVBar = Val.combine(
                needsVBar0,
                needsHBar0,
                content.totalHeightEstimateProperty(),
                hbar.heightProperty(),
                layoutHeight,
                (needsV, needsH, ch, hbh, lh) -> needsV || needsH && ch + hbh.doubleValue() > lh);

        Val<Boolean> shouldDisplayHorizontal = Val.flatMap(hbarPolicy, policy -> {
            switch (policy) {
                case NEVER:
                    return Val.constant(false);
                case ALWAYS:
                    return Val.constant(true);
                default: // AS_NEEDED
                    return needsHBar;
            }
        });
        Val<Boolean> shouldDisplayVertical = Val.flatMap(vbarPolicy, policy -> {
            switch (policy) {
                case NEVER:
                    return Val.constant(false);
                case ALWAYS:
                    return Val.constant(true);
                default: // AS_NEEDED
                    return needsVBar;
            }
        });

        // request layout later, because if currently in layout, the request is ignored
        shouldDisplayHorizontal.addListener(obs -> Platform.runLater(this::requestLayout));
        shouldDisplayVertical.addListener(obs -> Platform.runLater(this::requestLayout));

        hbar.visibleProperty().bind(shouldDisplayHorizontal);
        vbar.visibleProperty().bind(shouldDisplayVertical);

        contentFocusedListener = (obs, ov, nv) -> pseudoClassStateChanged(CONTENT_FOCUSED, nv);
        content.focusedProperty().addListener(contentFocusedListener);
        getChildren().addAll(content, hbar, vbar);
        getChildren().addListener((Observable obs) -> dispose());
    }

    /**
     * Constructs a VirtualizedScrollPane that only displays its horizontal and vertical scroll bars as needed
     */
    public VirtualizedScrollPane(@NamedArg("content") V content) {
        this(content, AS_NEEDED, AS_NEEDED);
    }

    /**
     * Does not unbind scrolling from Content before returning Content.
     * @return - the content
     */
    public V getContent() {
        return content;
    }

    /**
     * Unbinds scrolling from Content before returning Content.
     * @return - the content
     */
    public V removeContent() {
        getChildren().clear();
        return content;
    }

    private void dispose() {
        content.focusedProperty().removeListener(contentFocusedListener);
        hbarValue.removeListener(hbarValueListener);
        hPosEstimate.removeListener(hPosEstimateListener);
        vbarValue.removeListener(vbarValueListener);
        vPosEstimate.removeListener(vPosEstimateListener);
        unbindScrollBar(hbar);
        unbindScrollBar(vbar);
    }

    private void unbindScrollBar(ScrollBar bar) {
        bar.maxProperty().unbind();
        bar.unitIncrementProperty().unbind();
        bar.blockIncrementProperty().unbind();
        bar.visibleProperty().unbind();
    }

    @Override
    public Val<Double> totalWidthEstimateProperty() {
        return content.totalWidthEstimateProperty();
    }

    @Override
    public Val<Double> totalHeightEstimateProperty() {
        return content.totalHeightEstimateProperty();
    }

    @Override
    public Var<Double> estimatedScrollXProperty() {
        return content.estimatedScrollXProperty();
    }

    @Override
    public Var<Double> estimatedScrollYProperty() {
        return content.estimatedScrollYProperty();
    }

    @Override
    public void scrollXBy(double deltaX) {
        content.scrollXBy(deltaX);
    }

    @Override
    public void scrollYBy(double deltaY) {
        content.scrollYBy(deltaY);
    }

    @Override
    public void scrollXToPixel(double pixel) {
        content.scrollXToPixel(pixel);
    }

    @Override
    public void scrollYToPixel(double pixel) {
        content.scrollYToPixel(pixel);
    }

    @Override
    protected double computePrefWidth(double height) {
        return content.prefWidth(height);
    }

    @Override
    protected double computePrefHeight(double width) {
        return content.prefHeight(width);
    }

    @Override
    protected double computeMinWidth(double height) {
        return vbar.minWidth(-1);
    }

    @Override
    protected double computeMinHeight(double width) {
        return hbar.minHeight(-1);
    }

    @Override
    protected double computeMaxWidth(double height) {
        return content.maxWidth(height);
    }

    @Override
    protected double computeMaxHeight(double width) {
        return content.maxHeight(width);
    }

    @Override
    protected void layoutChildren() {
        double layoutWidth = snapSizeX(getLayoutBounds().getWidth());
        double layoutHeight = snapSizeY(getLayoutBounds().getHeight());
        boolean vbarVisible = vbar.isVisible();
        boolean hbarVisible = hbar.isVisible();
        double vbarWidth = snapSizeX(vbarVisible ? vbar.prefWidth(-1) : 0);
        double hbarHeight = snapSizeY(hbarVisible ? hbar.prefHeight(-1) : 0);

        double w = layoutWidth - vbarWidth;
        double h = layoutHeight - hbarHeight;

        content.resize(w, h);

        hbar.setVisibleAmount(w);
        vbar.setVisibleAmount(h);

        if(vbarVisible) {
            vbar.resizeRelocate(layoutWidth - vbarWidth, 0, vbarWidth, h);
        }

        if(hbarVisible) {
            hbar.resizeRelocate(0, layoutHeight - hbarHeight, w, hbarHeight);
        }
    }

    private void setHPosition(double pos) {
        Insets padding = content.getPadding();
        double offset = scrollbarPositionToOffset(
                pos,
                content.getLayoutBounds().getWidth(),
                padding.getLeft() + padding.getRight(),
                content.totalWidthEstimateProperty().getValue());
        if ( content.estimatedScrollXProperty().getValue() != offset ) {
            content.estimatedScrollXProperty().setValue(offset);
        }
    }

    private void setVPosition(double pos) {
        Insets padding = content.getPadding();
        double offset = scrollbarPositionToOffset(
                pos,
                content.getLayoutBounds().getHeight(),
                padding.getTop() + padding.getBottom(),
                content.totalHeightEstimateProperty().getValue());
        if ( content.estimatedScrollYProperty().getValue() != offset ) {
            content.estimatedScrollYProperty().setValue(offset);
        }
    }

    private static void setupUnitIncrement(ScrollBar bar) {
        bar.unitIncrementProperty().bind(new DoubleBinding() {
            { bind(bar.maxProperty(), bar.visibleAmountProperty()); }

            @Override
            protected double computeValue() {
                double max = bar.getMax();
                double visible = bar.getVisibleAmount();
                return max > visible
                        ? 16 / (max - visible) * max
                        : 0;
            }
        });
    }

    private static double offsetToScrollbarPosition(
            double contentOffset, double viewportSize, double padding, double contentSize) {
        return contentSize > viewportSize
                // rounding otherwise thin lines appear between cells, only visible with dark backgrounds/borders
                ? (double) Math.round( contentOffset / (contentSize - viewportSize + padding) * contentSize )
                : 0;
    }

    private static double scrollbarPositionToOffset(
            double scrollbarPos, double viewportSize, double padding, double contentSize) {
        return contentSize > viewportSize
                // rounding otherwise thin lines appear between cells, only visible with dark backgrounds/borders
                ? (double) Math.round( scrollbarPos / contentSize * (contentSize - viewportSize + padding) )
                : 0;
    }
}
