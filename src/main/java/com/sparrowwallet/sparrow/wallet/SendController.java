package com.sparrowwallet.sparrow.wallet;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.BitcoinUnit;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.InvalidAddressException;
import com.sparrowwallet.drongo.address.P2PKHAddress;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppController;
import com.sparrowwallet.sparrow.CurrencyRate;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.*;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.ExchangeSource;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.util.StringConverter;
import org.controlsfx.validation.ValidationResult;
import org.controlsfx.validation.ValidationSupport;
import org.controlsfx.validation.Validator;
import org.controlsfx.validation.decoration.StyleClassValidationDecoration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SendController extends WalletFormController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(SendController.class);

    public static final List<Integer> TARGET_BLOCKS_RANGE = List.of(1, 2, 3, 4, 5, 10, 25, 50, 100, 500);

    public static final double FALLBACK_FEE_RATE = 20000d / 1000;

    @FXML
    private TabPane paymentTabs;

    @FXML
    private Slider targetBlocks;

    @FXML
    private CopyableLabel feeRate;

    @FXML
    private TextField fee;

    @FXML
    private ComboBox<BitcoinUnit> feeAmountUnit;

    @FXML
    private FiatLabel fiatFeeAmount;

    @FXML
    private FeeRatesChart feeRatesChart;

    @FXML
    private TransactionDiagram transactionDiagram;

    @FXML
    private Button clearButton;

    @FXML
    private Button createButton;

    private StackPane tabHeader;

    private final BooleanProperty userFeeSet = new SimpleBooleanProperty(false);

    private final ObjectProperty<UtxoSelector> utxoSelectorProperty = new SimpleObjectProperty<>(null);

    private final ObjectProperty<UtxoFilter> utxoFilterProperty = new SimpleObjectProperty<>(null);

    private final ObjectProperty<WalletTransaction> walletTransactionProperty = new SimpleObjectProperty<>(null);

    private final ObjectProperty<WalletTransaction> createdWalletTransactionProperty = new SimpleObjectProperty<>(null);

    private final BooleanProperty insufficientInputsProperty = new SimpleBooleanProperty(false);

    private final StringProperty utxoLabelSelectionProperty = new SimpleStringProperty("");

    private final ChangeListener<String> feeListener = new ChangeListener<>() {
        @Override
        public void changed(ObservableValue<? extends String> observable, String oldValue, String newValue) {
            userFeeSet.set(true);
            if(newValue.isEmpty()) {
                fiatFeeAmount.setText("");
            } else {
                setFiatFeeAmount(AppController.getFiatCurrencyExchangeRate(), getFeeValueSats());
            }

            setTargetBlocks(getTargetBlocks());
            updateTransaction();
        }
    };

    private final ChangeListener<Number> targetBlocksListener = new ChangeListener<>() {
        @Override
        public void changed(ObservableValue<? extends Number> observable, Number oldValue, Number newValue) {
            Map<Integer, Double> targetBlocksFeeRates = getTargetBlocksFeeRates();
            Integer target = getTargetBlocks();

            if(targetBlocksFeeRates != null) {
                setFeeRate(targetBlocksFeeRates.get(target));
                feeRatesChart.select(target);
            } else {
                feeRate.setText("Unknown");
            }

            Tooltip tooltip = new Tooltip("Target confirmation within " + target + " blocks");
            targetBlocks.setTooltip(tooltip);

            userFeeSet.set(false);
            for(Tab tab : paymentTabs.getTabs()) {
                PaymentController controller = (PaymentController)tab.getUserData();
                controller.revalidate();
            }
            updateTransaction();
        }
    };

    private ValidationSupport validationSupport;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        EventManager.get().register(this);
    }

    @Override
    public void initializeView() {
        addValidation();

        addPaymentTab();
        Platform.runLater(() -> {
            StackPane stackPane = (StackPane)paymentTabs.lookup(".tab-header-area");
            if(stackPane != null) {
                tabHeader = stackPane;
                tabHeader.managedProperty().bind(tabHeader.visibleProperty());
                tabHeader.setVisible(false);
                paymentTabs.getStyleClass().remove("initial");
            }
        });

        paymentTabs.getTabs().addListener((ListChangeListener<Tab>) c -> {
            if(tabHeader != null) {
                tabHeader.setVisible(c.getList().size() > 1);
            }

            if(c.getList().size() > 1) {
                if(!paymentTabs.getStyleClass().contains("multiple-tabs")) {
                    paymentTabs.getStyleClass().add("multiple-tabs");
                }
                paymentTabs.getTabs().forEach(tab -> tab.setClosable(true));
            } else {
                paymentTabs.getStyleClass().remove("multiple-tabs");
                Tab remainingTab = paymentTabs.getTabs().get(0);
                remainingTab.setClosable(false);
                remainingTab.setText("1");
            }

            updateTransaction();
        });

        insufficientInputsProperty.addListener((observable, oldValue, newValue) -> {
            for(Tab tab : paymentTabs.getTabs()) {
                PaymentController controller = (PaymentController)tab.getUserData();
                controller.revalidate();
            }
            revalidate(fee, feeListener);
        });

        targetBlocks.setMin(0);
        targetBlocks.setMax(TARGET_BLOCKS_RANGE.size() - 1);
        targetBlocks.setMajorTickUnit(1);
        targetBlocks.setMinorTickCount(0);
        targetBlocks.setLabelFormatter(new StringConverter<Double>() {
            @Override
            public String toString(Double object) {
                String blocks = Integer.toString(TARGET_BLOCKS_RANGE.get(object.intValue()));
                return (object.intValue() == TARGET_BLOCKS_RANGE.size() - 1) ? blocks + "+" : blocks;
            }

            @Override
            public Double fromString(String string) {
                return (double)TARGET_BLOCKS_RANGE.indexOf(Integer.valueOf(string.replace("+", "")));
            }
        });
        targetBlocks.valueProperty().addListener(targetBlocksListener);

        feeRatesChart.initialize();
        Map<Integer, Double> targetBlocksFeeRates = getTargetBlocksFeeRates();
        if(targetBlocksFeeRates != null) {
            feeRatesChart.update(targetBlocksFeeRates);
        } else {
            feeRate.setText("Unknown");
        }

        int defaultTarget = TARGET_BLOCKS_RANGE.get((TARGET_BLOCKS_RANGE.size() / 2) - 1);
        int index = TARGET_BLOCKS_RANGE.indexOf(defaultTarget);
        targetBlocks.setValue(index);
        feeRatesChart.select(defaultTarget);

        fee.setTextFormatter(new CoinTextFormatter());
        fee.textProperty().addListener(feeListener);

        BitcoinUnit unit = getBitcoinUnit(Config.get().getBitcoinUnit());
        feeAmountUnit.getSelectionModel().select(BitcoinUnit.BTC.equals(unit) ? 0 : 1);
        feeAmountUnit.valueProperty().addListener((observable, oldValue, newValue) -> {
            Long value = getFeeValueSats(oldValue);
            if(value != null) {
                setFeeValueSats(value);
            }
        });

        userFeeSet.addListener((observable, oldValue, newValue) -> {
            feeRatesChart.select(0);

            Node thumb = getSliderThumb();
            if(thumb != null) {
                if(newValue) {
                    thumb.getStyleClass().add("inactive");
                } else {
                    thumb.getStyleClass().remove("inactive");
                }
            }
        });

        utxoLabelSelectionProperty.addListener((observable, oldValue, newValue) -> {
            clearButton.setText("Clear" + newValue);
        });

        utxoSelectorProperty.addListener((observable, oldValue, utxoSelector) -> {
            updateMaxClearButtons(utxoSelector, utxoFilterProperty.get());
        });

        utxoFilterProperty.addListener((observable, oldValue, utxoFilter) -> {
            updateMaxClearButtons(utxoSelectorProperty.get(), utxoFilter);
        });

        walletTransactionProperty.addListener((observable, oldValue, walletTransaction) -> {
            if(walletTransaction != null) {
                for(int i = 0; i < paymentTabs.getTabs().size(); i++) {
                    Payment payment = walletTransaction.getPayments().get(i);
                    PaymentController controller = (PaymentController)paymentTabs.getTabs().get(i).getUserData();
                    controller.setPayment(payment);
                }

                double feeRate = walletTransaction.getFeeRate();
                if(userFeeSet.get()) {
                    setTargetBlocks(getTargetBlocks(feeRate));
                } else {
                    setFeeValueSats(walletTransaction.getFee());
                }

                setFeeRate(feeRate);
            }

            transactionDiagram.update(walletTransaction);
            createButton.setDisable(walletTransaction == null || isInsufficientFeeRate());
        });
    }

    public BitcoinUnit getBitcoinUnit(BitcoinUnit bitcoinUnit) {
        BitcoinUnit unit = bitcoinUnit;
        if(unit == null || unit.equals(BitcoinUnit.AUTO)) {
            unit = getWalletForm().getWallet().getAutoUnit();
        }
        return unit;
    }

    public ValidationSupport getValidationSupport() {
        return validationSupport;
    }

    private void addValidation() {
        validationSupport = new ValidationSupport();
        validationSupport.registerValidator(fee, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Insufficient Inputs", userFeeSet.get() && insufficientInputsProperty.get()),
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Insufficient Fee", getFeeValueSats() != null && getFeeValueSats() == 0),
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Insufficient Fee Rate", isInsufficientFeeRate())
        ));

        validationSupport.setValidationDecorator(new StyleClassValidationDecoration());
        validationSupport.setErrorDecorationEnabled(false);
    }

    public Tab addPaymentTab() {
        Tab tab = getPaymentTab();
        paymentTabs.getTabs().add(tab);
        paymentTabs.getSelectionModel().select(tab);
        return tab;
    }

    public Tab getPaymentTab() {
        Tab tab = new Tab(Integer.toString(paymentTabs.getTabs().size() + 1));

        try {
            FXMLLoader paymentLoader = new FXMLLoader(AppController.class.getResource("wallet/payment.fxml"));
            tab.setContent(paymentLoader.load());
            PaymentController controller = paymentLoader.getController();
            controller.setSendController(this);
            controller.initializeView();
            tab.setUserData(controller);
            return tab;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Payment> getPayments() {
        List<Payment> payments = new ArrayList<>();
        for(Tab tab : paymentTabs.getTabs()) {
            PaymentController controller = (PaymentController)tab.getUserData();
            payments.add(controller.getPayment());
        }

        return payments;
    }

    public void updateTransaction() {
        updateTransaction(null);
    }

    public void updateTransaction(boolean sendAll) {
        try {
            if(paymentTabs.getTabs().size() == 1) {
                PaymentController controller = (PaymentController)paymentTabs.getTabs().get(0).getUserData();
                updateTransaction(List.of(controller.getPayment(sendAll)));
            } else {
                updateTransaction(null);
            }
        } catch(IllegalStateException e) {
            //ignore
        }
    }

    public void updateTransaction(List<Payment> transactionPayments) {
        try {
            List<Payment> payments = transactionPayments != null ? transactionPayments : getPayments();
            if(!userFeeSet.get() || (getFeeValueSats() != null && getFeeValueSats() > 0)) {
                Wallet wallet = getWalletForm().getWallet();
                Long userFee = userFeeSet.get() ? getFeeValueSats() : null;
                Integer currentBlockHeight = AppController.getCurrentBlockHeight();
                boolean groupByAddress = Config.get().isGroupByAddress();
                boolean includeMempoolChange = Config.get().isIncludeMempoolChange();
                WalletTransaction walletTransaction = wallet.createWalletTransaction(getUtxoSelectors(), getUtxoFilters(), payments, getFeeRate(), getMinimumFeeRate(), userFee, currentBlockHeight, groupByAddress, includeMempoolChange);
                walletTransactionProperty.setValue(walletTransaction);
                insufficientInputsProperty.set(false);

                return;
            }
        } catch(InvalidAddressException | IllegalStateException e) {
            //ignore
        } catch(InsufficientFundsException e) {
            insufficientInputsProperty.set(true);
        }

        walletTransactionProperty.setValue(null);
    }

    private List<UtxoSelector> getUtxoSelectors() throws InvalidAddressException {
        if(utxoSelectorProperty.get() != null) {
            return List.of(utxoSelectorProperty.get());
        }

        Wallet wallet = getWalletForm().getWallet();
        long noInputsFee = wallet.getNoInputsFee(getPayments(), getFeeRate());
        long costOfChange = wallet.getCostOfChange(getFeeRate(), getMinimumFeeRate());

        return List.of(new BnBUtxoSelector(noInputsFee, costOfChange), new KnapsackUtxoSelector(noInputsFee));
    }

    private List<UtxoFilter> getUtxoFilters() {
        UtxoFilter utxoFilter = utxoFilterProperty.get();
        if(utxoFilter != null) {
            return List.of(utxoFilter);
        }

        return Collections.emptyList();
    }

    private Long getFeeValueSats() {
        return getFeeValueSats(feeAmountUnit.getSelectionModel().getSelectedItem());
    }

    private Long getFeeValueSats(BitcoinUnit bitcoinUnit) {
        if(fee.getText() != null && !fee.getText().isEmpty()) {
            double fieldValue = Double.parseDouble(fee.getText().replaceAll(",", ""));
            return bitcoinUnit.getSatsValue(fieldValue);
        }

        return null;
    }

    private void setFeeValueSats(long feeValue) {
        fee.textProperty().removeListener(feeListener);
        DecimalFormat df = new DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        df.setMaximumFractionDigits(8);
        fee.setText(df.format(feeAmountUnit.getValue().getValue(feeValue)));
        fee.textProperty().addListener(feeListener);
        setFiatFeeAmount(AppController.getFiatCurrencyExchangeRate(), feeValue);
    }

    private Integer getTargetBlocks() {
        int index = (int)targetBlocks.getValue();
        return TARGET_BLOCKS_RANGE.get(index);
    }

    private Integer getTargetBlocks(double feeRate) {
        Map<Integer, Double> targetBlocksFeeRates = getTargetBlocksFeeRates();
        int maxTargetBlocks = 1;
        for(Integer targetBlocks : targetBlocksFeeRates.keySet()) {
            maxTargetBlocks = Math.max(maxTargetBlocks, targetBlocks);
            Double candidate = targetBlocksFeeRates.get(targetBlocks);
            if(feeRate > candidate) {
                return targetBlocks;
            }
        }

        return maxTargetBlocks;
    }

    private void setTargetBlocks(Integer target) {
        targetBlocks.valueProperty().removeListener(targetBlocksListener);
        int index = TARGET_BLOCKS_RANGE.indexOf(target);
        targetBlocks.setValue(index);
        feeRatesChart.select(target);
        targetBlocks.valueProperty().addListener(targetBlocksListener);
    }

    private Map<Integer, Double> getTargetBlocksFeeRates() {
        Map<Integer, Double> retrievedFeeRates = AppController.getTargetBlockFeeRates();
        if(retrievedFeeRates == null) {
            retrievedFeeRates = TARGET_BLOCKS_RANGE.stream().collect(Collectors.toMap(java.util.function.Function.identity(), v -> FALLBACK_FEE_RATE,
                    (u, v) -> { throw new IllegalStateException("Duplicate target blocks"); },
                    LinkedHashMap::new));
        }

        return retrievedFeeRates;
    }

    public Double getFeeRate() {
        return getTargetBlocksFeeRates().get(getTargetBlocks());
    }

    private Double getMinimumFeeRate() {
        Optional<Double> optMinFeeRate = getTargetBlocksFeeRates().values().stream().min(Double::compareTo);
        Double minRate = optMinFeeRate.orElse(FALLBACK_FEE_RATE);
        return Math.max(minRate, Transaction.DUST_RELAY_TX_FEE);
    }

    public boolean isInsufficientFeeRate() {
        return walletTransactionProperty.get() != null && walletTransactionProperty.get().getFeeRate() < AppController.getMinimumRelayFeeRate();
    }

    private void setFeeRate(Double feeRateAmt) {
        feeRate.setText(String.format("%.2f", feeRateAmt) + " sats/vByte");
    }

    private Node getSliderThumb() {
        return targetBlocks.lookup(".thumb");
    }

    private void setFiatFeeAmount(CurrencyRate currencyRate, Long amount) {
        if(amount != null && currencyRate != null && currencyRate.isAvailable()) {
            fiatFeeAmount.set(currencyRate, amount);
        }
    }

    private void updateMaxClearButtons(UtxoSelector utxoSelector, UtxoFilter utxoFilter) {
        if(utxoSelector instanceof PresetUtxoSelector) {
            PresetUtxoSelector presetUtxoSelector = (PresetUtxoSelector)utxoSelector;
            int num = presetUtxoSelector.getPresetUtxos().size();
            String selection = " (" + num + " UTXO" + (num != 1 ? "s" : "") + " selected)";
            utxoLabelSelectionProperty.set(selection);
        } else if(utxoFilter instanceof ExcludeUtxoFilter) {
            ExcludeUtxoFilter excludeUtxoFilter = (ExcludeUtxoFilter)utxoFilter;
            int num = excludeUtxoFilter.getExcludedUtxos().size();
            String exclusion = " (" + num + " UTXO" + (num != 1 ? "s" : "") + " excluded)";
            utxoLabelSelectionProperty.set(exclusion);
        } else {
            utxoLabelSelectionProperty.set("");
        }
    }

    public void clear(ActionEvent event) {
        boolean firstTab = true;
        for(Iterator<Tab> iterator = paymentTabs.getTabs().iterator(); iterator.hasNext(); ) {
            PaymentController controller = (PaymentController)iterator.next().getUserData();
            if(firstTab) {
                controller.clear();
                firstTab = false;
            } else {
                EventManager.get().unregister(controller);
                iterator.remove();
            }
        }

        fee.textProperty().removeListener(feeListener);
        fee.setText("");
        fee.textProperty().addListener(feeListener);

        fiatFeeAmount.setText("");

        userFeeSet.set(false);
        targetBlocks.setValue(4);
        utxoSelectorProperty.setValue(null);
        utxoFilterProperty.setValue(null);
        walletTransactionProperty.setValue(null);
        createdWalletTransactionProperty.set(null);

        validationSupport.setErrorDecorationEnabled(false);
    }

    public UtxoSelector getUtxoSelector() {
        return utxoSelectorProperty.get();
    }

    public ObjectProperty<UtxoSelector> utxoSelectorProperty() {
        return utxoSelectorProperty;
    }

    public boolean isInsufficientInputs() {
        return insufficientInputsProperty.get();
    }

    public BooleanProperty insufficientInputsProperty() {
        return insufficientInputsProperty;
    }

    public WalletTransaction getWalletTransaction() {
        return walletTransactionProperty.get();
    }

    public ObjectProperty<WalletTransaction> walletTransactionProperty() {
        return walletTransactionProperty;
    }

    public String getUtxoLabelSelection() {
        return utxoLabelSelectionProperty.get();
    }

    public StringProperty utxoLabelSelectionProperty() {
        return utxoLabelSelectionProperty;
    }

    public TabPane getPaymentTabs() {
        return paymentTabs;
    }

    public Button getCreateButton() {
        return createButton;
    }

    private void revalidate(TextField field, ChangeListener<String> listener) {
        field.textProperty().removeListener(listener);
        String amt = field.getText();
        field.setText(amt + "0");
        field.setText(amt);
        field.textProperty().addListener(listener);
    }

    public void createTransaction(ActionEvent event) {
        if(log.isDebugEnabled()) {
            Map<WalletNode, String> nodeHashes = walletTransactionProperty.get().getSelectedUtxos().values().stream().collect(Collectors.toMap(Function.identity(), node -> ElectrumServer.getScriptHash(walletForm.getWallet(), node)));
            Map<WalletNode, String> changeHash = Collections.emptyMap();
            if(walletTransactionProperty.get().getChangeNode() != null) {
                changeHash = Map.of(walletTransactionProperty.get().getChangeNode(), ElectrumServer.getScriptHash(walletForm.getWallet(), walletTransactionProperty.get().getChangeNode()));
            }
            log.debug("Creating tx " + walletTransactionProperty.get().getTransaction().getTxId() + ", expecting notifications for \ninputs \n" + nodeHashes + " and \nchange \n" + changeHash);
        }

        addWalletTransactionNodes();
        createdWalletTransactionProperty.set(walletTransactionProperty.get());
        PSBT psbt = walletTransactionProperty.get().createPSBT();
        EventManager.get().post(new ViewPSBTEvent(walletTransactionProperty.get().getPayments().get(0).getLabel(), psbt));
    }

    private void addWalletTransactionNodes() {
        WalletTransaction walletTransaction = walletTransactionProperty.get();
        Set<WalletNode> nodes = new LinkedHashSet<>(walletTransaction.getSelectedUtxos().values());
        nodes.add(walletTransaction.getChangeNode());
        List<WalletNode> consolidationNodes = walletTransaction.getConsolidationSendNodes();
        nodes.addAll(consolidationNodes);

        //All wallet nodes applicable to this transaction are stored so when the subscription status for one is updated, the history for all can be fetched in one atomic update
        walletForm.addWalletTransactionNodes(nodes);
    }

    @Subscribe
    public void walletNodesChanged(WalletNodesChangedEvent event) {
        if(event.getWallet().equals(walletForm.getWallet())) {
            clear(null);
        }
    }

    @Subscribe
    public void walletHistoryChanged(WalletHistoryChangedEvent event) {
        if(event.getWallet().equals(walletForm.getWallet()) && createdWalletTransactionProperty.get() != null) {
            if(createdWalletTransactionProperty.get().getSelectedUtxos() != null && allSelectedUtxosSpent(event.getHistoryChangedNodes())) {
                clear(null);
            } else {
                updateTransaction();
            }
        }
    }

    private boolean allSelectedUtxosSpent(List<WalletNode> historyChangedNodes) {
        Set<BlockTransactionHashIndex> unspentUtxos = new HashSet<>(createdWalletTransactionProperty.get().getSelectedUtxos().keySet());

        for(Map.Entry<BlockTransactionHashIndex, WalletNode> selectedUtxoEntry : createdWalletTransactionProperty.get().getSelectedUtxos().entrySet()) {
            BlockTransactionHashIndex utxo = selectedUtxoEntry.getKey();
            WalletNode utxoWalletNode = selectedUtxoEntry.getValue();

            for(WalletNode changedNode : historyChangedNodes) {
                if(utxoWalletNode.equals(changedNode)) {
                    Optional<BlockTransactionHashIndex> spentTxo = changedNode.getTransactionOutputs().stream().filter(txo -> txo.getHash().equals(utxo.getHash()) && txo.getIndex() == utxo.getIndex() && txo.isSpent()).findAny();
                    if(spentTxo.isPresent()) {
                        unspentUtxos.remove(utxo);
                    }
                }
            }
        }

        return unspentUtxos.isEmpty();
    }

    @Subscribe
    public void walletEntryLabelChanged(WalletEntryLabelChangedEvent event) {
        if(event.getWallet().equals(walletForm.getWallet())) {
            updateTransaction();
        }
    }

    @Subscribe
    public void feeRatesUpdated(FeeRatesUpdatedEvent event) {
        feeRatesChart.update(event.getTargetBlockFeeRates());
        feeRatesChart.select(getTargetBlocks());
        setFeeRate(event.getTargetBlockFeeRates().get(getTargetBlocks()));
    }

    @Subscribe
    public void spendUtxos(SpendUtxoEvent event) {
        if(!event.getUtxoEntries().isEmpty() && event.getUtxoEntries().get(0).getWallet().equals(getWalletForm().getWallet())) {
            List<BlockTransactionHashIndex> utxos = event.getUtxoEntries().stream().map(HashIndexEntry::getHashIndex).collect(Collectors.toList());
            utxoSelectorProperty.set(new PresetUtxoSelector(utxos));
            utxoFilterProperty.set(null);
            updateTransaction(true);
        }
    }

    @Subscribe
    public void bitcoinUnitChanged(BitcoinUnitChangedEvent event) {
        BitcoinUnit unit = getBitcoinUnit(event.getBitcoinUnit());
        feeAmountUnit.getSelectionModel().select(BitcoinUnit.BTC.equals(unit) ? 0 : 1);
    }

    @Subscribe
    public void fiatCurrencySelected(FiatCurrencySelectedEvent event) {
        if(event.getExchangeSource() == ExchangeSource.NONE) {
            fiatFeeAmount.setCurrency(null);
            fiatFeeAmount.setBtcRate(0.0);
        }
    }

    @Subscribe
    public void exchangeRatesUpdated(ExchangeRatesUpdatedEvent event) {
        setFiatFeeAmount(event.getCurrencyRate(), getFeeValueSats());
    }

    @Subscribe
    public void excludeUtxo(ExcludeUtxoEvent event) {
        if(event.getWalletTransaction() == walletTransactionProperty.get()) {
            UtxoSelector utxoSelector = utxoSelectorProperty.get();
            if(utxoSelector instanceof MaxUtxoSelector) {
                Collection<BlockTransactionHashIndex> utxos = walletForm.getWallet().getWalletUtxos().keySet();
                utxos.remove(event.getUtxo());
                if(utxoFilterProperty.get() instanceof ExcludeUtxoFilter) {
                    ExcludeUtxoFilter existingUtxoFilter = (ExcludeUtxoFilter)utxoFilterProperty.get();
                    utxos.removeAll(existingUtxoFilter.getExcludedUtxos());
                }
                PresetUtxoSelector presetUtxoSelector = new PresetUtxoSelector(utxos);
                utxoSelectorProperty.set(presetUtxoSelector);
                updateTransaction(true);
            } else if(utxoSelector instanceof PresetUtxoSelector) {
                PresetUtxoSelector presetUtxoSelector = new PresetUtxoSelector(((PresetUtxoSelector)utxoSelector).getPresetUtxos());
                presetUtxoSelector.getPresetUtxos().remove(event.getUtxo());
                utxoSelectorProperty.set(presetUtxoSelector);
                updateTransaction(true);
            } else {
                ExcludeUtxoFilter utxoFilter = new ExcludeUtxoFilter();
                if(utxoFilterProperty.get() instanceof ExcludeUtxoFilter) {
                    ExcludeUtxoFilter existingUtxoFilter = (ExcludeUtxoFilter)utxoFilterProperty.get();
                    utxoFilter.getExcludedUtxos().addAll(existingUtxoFilter.getExcludedUtxos());
                }

                utxoFilter.getExcludedUtxos().add(event.getUtxo());
                utxoFilterProperty.set(utxoFilter);
                updateTransaction();
            }
        }
    }
}
