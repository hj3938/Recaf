package me.coley.recaf.ui.controls;

import com.google.common.collect.Sets;
import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.spi.AttachProvider;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import me.coley.recaf.control.gui.GuiController;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import me.coley.recaf.util.DelayableAction;
import me.coley.recaf.util.LangUtil;
import me.coley.recaf.util.Log;
import me.coley.recaf.util.SelfReferenceUtil;
import me.coley.recaf.util.UiUtil;
import me.coley.recaf.util.struct.Expireable;
import me.coley.recaf.workspace.FileSystemResource;
import me.coley.recaf.workspace.JavaResource;
import me.coley.recaf.workspace.Workspace;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * UI for attaching to other VM's.
 *
 * @author Matt
 */
public class AttachPane extends BorderPane {
	private static final long UPDATE_TIME = 1000;
	private final Map<String, VMInfo> info = new TreeMap<>();
	private final ListView<VMInfo> list = new ListView<>();
	private final BorderPane view = new BorderPane();
	private final GuiController controller;

	/**
	 * @param controller
	 * 		Controller to use.
	 */
	public AttachPane(GuiController controller) {
		this.controller = controller;
		refreshVmList();
		setup();
	}

	/**
	 * Setup primary components.
	 */
	private void setup() {
		view.getStyleClass().add("vm-view");
		list.getStyleClass().add("vm-list");
		list.setItems(FXCollections.observableArrayList());
		list.setCellFactory(c -> new VMCell());
		list.getSelectionModel().selectedItemProperty().addListener((ob, o, n) -> {
			view.setCenter(createVmDisplay(n));
		});
		SplitPane split = new SplitPane(list, view);
		SplitPane.setResizableWithParent(list, Boolean.FALSE);
		split.setDividerPositions(0.37);
		setCenter(split);
		// Create thread to continually update vm info (remove dead vms, add new ones)
		new Thread(() -> {
			while(true) {
				try {
					Thread.sleep(UPDATE_TIME);
				} catch(InterruptedException ex) { /* ignored */ }
				if (controller.windows().getAttachWindow().isShowing()) {
					refreshVmList();
				}
			}
		}).start();
	}

	/**
	 * Create a display for the given vm.
	 * @param vm JVM to potentially attach to.
	 * @return Node containing VM properties, and actions.
	 */
	private Node createVmDisplay(VMInfo vm) {
		BorderPane pane = new BorderPane();
		HBox horizontal = new HBox();
		horizontal.getStyleClass().add("vm-buttons");
		horizontal.getChildren().addAll(
				new ActionButton("Attach", () -> attach(vm)),
				new ActionButton("Copy to workspace", () -> copy(vm)));
		pane.setTop(horizontal);
		TableView<Map.Entry<String,String>> table = new TableView<>();
		table.getStyleClass().add("vm-info-table");
		TableColumn<Map.Entry<String,String>, String> keyColumn = new TableColumn<>("Key");
		TableColumn<Map.Entry<String,String>, String> valueColumn = new TableColumn<>("Value");
		keyColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getKey()));
		valueColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getValue()));
		table.getColumns().add(keyColumn);
		table.getColumns().add(valueColumn);
		List<Map.Entry<String, String>> entries = new ArrayList<>(vm.getProperties().entrySet());
		table.setItems(FXCollections.observableArrayList(entries));
		valueColumn.prefWidthProperty().bind(pane.widthProperty().subtract(keyColumn.widthProperty()).subtract(15));
		pane.setCenter(table);
		return pane;
	}

	/**
	 * Attach to the VM.
	 *
	 * @param vm
	 * 		VM to attach to.
	 */
	private void attach(VMInfo vm) {
		Runnable onSuccess = () -> {
			Log.info("Successfully attached to VM '{}' ... Closing current instance.", vm.getPid());
			try {
				vm.detach();
			} catch(Exception ex) {
				Log.error(ex, "Failed to detach from VM '{}'", vm.getPid());
			}
			controller.exit();
		};
		Consumer<Exception> onError = (ex) -> {
			ExceptionAlert.show(ex, "Rcaf failed to connect to the target VM: " +  vm.getPid());
		};
		vm.attach(onSuccess, onError);
	}

	/**
	 * Copy the VM's information to the current instance.
	 *
	 * @param vm
	 * 		VM to attach to.
	 */
	private void copy(VMInfo vm) {
		List<JavaResource> libs = new ArrayList<>();
		String path = vm.getProperties().get("java.class.path");
		String javaHome = vm.getProperties().get("java.home");
		String localDir = vm.getProperties().get("user.dir");
		if(path != null && !path.isEmpty()) {
			String[] items = path.split(";");
			for(String item : items) {
				Path filePath = Paths.get(item);
				boolean isAbsolute = filePath.isAbsolute();
				File file;
				if (isAbsolute)
					file = new File(item);
				else
					file = Paths.get(localDir, item).toFile();
				if(!file.exists() || file.getAbsolutePath().startsWith(javaHome))
					continue;
				try {
					libs.add(FileSystemResource.of(file));
				} catch(Exception ex) {
					Log.warn("Could not load classpath item '{}'", item);
				}
			}
		}
		promptPrimary(libs);

	}

	private void promptPrimary(List<JavaResource> libs) {
		BorderPane pane = new BorderPane();
		Stage stage = controller.windows().window(LangUtil.translate("ui.menubar.file.newwizard"), pane);
		ResourceComboBox comboResources = new ResourceComboBox(controller);
		comboResources.setItems(FXCollections.observableArrayList(libs));
		ActionButton btn = new ActionButton(LangUtil.translate("misc.load"), () -> {
			JavaResource primary = comboResources.getSelectionModel().getSelectedItem();
			libs.remove(primary);
			controller.setWorkspace(new Workspace(primary, libs));
			stage.close();
		});
		btn.prefWidthProperty().bind(pane.widthProperty());
		btn.setDisable(true);
		comboResources.getSelectionModel().selectedItemProperty().addListener((ob, old, cur) -> {
			btn.setDisable(false);
		});
		comboResources.getSelectionModel().select(0);
		BorderPane.setAlignment(comboResources, Pos.CENTER);
		pane.setTop(comboResources);
		pane.setCenter(btn);
		stage.show();
	}

	/**
	 * Returns the currently detected VM's. The list can be updated via {@link #refreshVmList()}.
	 *
	 * @return Currently detected VM's.
	 */
	public Collection<VMInfo> getInfo() {
		return info.values();
	}

	/**
	 * Reset the {@link #getInfo VM information} list.
	 */
	public void refreshVmList() {
		Set<String> oldKeys = new HashSet<>(info.keySet());
		Set<String> newKeys = new HashSet<>();
		for(VirtualMachineDescriptor descr : VirtualMachine.list()) {
			try {
				// Attach
				AttachProvider provider = descr.provider();
				VirtualMachine machine = provider.attachVirtualMachine(descr);
				String id = machine.id();
				newKeys.add(id);
				if (info.containsKey(id))
					continue;
				if(!id.matches("\\d+")) {
					machine.detach();
					continue;
				}
				// Store info
				int pid = Integer.parseInt(machine.id());
				info.put(id, new VMInfo(machine, pid, descr.displayName()));
			} catch(Exception ex) {
				// Can't connect? Log the failure
				String cause = ex.getMessage();
				if (cause == null)
					cause = ex.toString();
				Log.warn("Failed to attach to remote vm '{}' - Cause: {}", descr.id(), cause);
			}
		}
		// Remove any old VM
		// Add any new VM
		Set<String> removed = Sets.difference(oldKeys, newKeys);
		Set<String> added = Sets.difference(newKeys, oldKeys);
		Platform.runLater(() -> {
			removed.forEach(r -> {
				VMInfo vm = info.remove(r);
				list.getItems().remove(vm);
			});
			added.forEach(a -> {
				VMInfo vm = info.get(a);
				list.getItems().add(vm);
			});
		});
	}

	/**
	 * Cell to display VM's.
	 */
	private static class VMCell extends ListCell<VMInfo> {
		@Override
		public void updateItem(VMInfo item, boolean empty) {
			super.updateItem(item, empty);
			if(empty) {
				setGraphic(null);
			} else {
				getStyleClass().add("vm-cell");
				BorderPane pane = new BorderPane();
				pane.setLeft(new IconView(UiUtil.getFileIcon("app.jar")));
				pane.setLeft(new BorderPane(pane.getLeft()));
				pane.getLeft().getStyleClass().add("vm-icon");
				pane.setCenter(new SubLabeled(item.pid + " - " + item.getMainClass(),
						"VM: " + item.getVmName()  +
						"\nVersion: " + item.getJavaVersion()));
				setGraphic(pane);
			}
		}
	}

	/**
	 * Remove VM information.
	 */
	public static class VMInfo {
		private static final long UPDATE_THRESHOLD = 5000;
		private final VirtualMachine machine;
		private final int pid;
		private final String displayName;
		private final Expireable<Map<String, String>> properties;

		/**
		 * @param machine
		 * 		The wrapped VM.
		 * @param pid
		 * 		VM's process identifier.
		 * @param displayName
		 * VM's display name;
		 */
		private VMInfo(VirtualMachine machine, int pid, String displayName) {
			this.machine = machine;
			this.pid = pid;
			this.displayName = displayName;
			this.properties = new Expireable<>(UPDATE_THRESHOLD, () -> {
				Map<String, String> properties = new TreeMap<>();
				try {
					machine.getSystemProperties()
							.forEach((k, v) -> properties.put(k.toString(), v.toString()));
				} catch(Exception ex) {
					Log.warn("Failed to fetch properties from VM: " + machine.id());
				}
				return properties;
			});
		}

		/**
		 * @return VM's process identifier.
		 */
		public int getPid() {
			return pid;
		}

		/**
		 * @return VM's properties.
		 */
		public Map<String, String> getProperties() {
			return properties.get();
		}

		/**
		 * @return VM's name.
		 */
		public String getVmName() {
			return getProperties().getOrDefault("java.vm.name", "?");
		}

		/**
		 *
		 * @return Version of java supported by the VM.
		 */
		public String getJavaVersion() {
			return getProperties().getOrDefault("java.version", "?");
		}

		/**
		 * @return Classpath items of the VM.
		 */
		public List<String> getClasspath() {
			String path =  getProperties().get("java.class.path");
			if (path == null || path.isEmpty())
				return Collections.emptyList();
			return Arrays.asList(path.split(";"));
		}

		/**
		 * @return Main class of the VM. May be {@code null}.
		 */
		public String getMainClass() {
			// Get source string to find main class name from
			String source = displayName;
			if (source == null || source.isEmpty()) {
				source = getProperties().get("sun.java.command");
			}
			// Still null/missing? Give up
			if (source == null || source.isEmpty())
				return "<?>";
			String trim = source.trim();
			int end = trim.indexOf(' ');
			if (end == -1)
				end = trim.length();
			return trim.substring(0, end);
		}

		/**
		 * Attempt to attach to the VM.
		 * The following exceptions may be fed to the passed handler:
		 * <ul>
		 * <li><b>IOException</b> - When the Recaf agent could not be fetched.</li>
		 * <li><b>URISyntaxException</b> - When Recaf could not find its own running context.</li>
		 * <li><b>AgentInitializationException</b> - When the agent failed to initialize in the
		 * target VM.</li>
		 * <li><b>AgentLoadException</b> - When the agent failed to load in the target VM.</li>
		 * </ul>
		 *
		 * @param onSuccess
		 * 		Action to run on successfully connecting to the target VM.
		 * @param onError
		 * 		Action to run on failure to connect to the target VM.
		 */
		public void attach(Runnable onSuccess, Consumer<Exception> onError) {
			new Thread(() -> {
				AtomicBoolean isSuccess = new AtomicBoolean(true);
				Exception thrown = null;
				try {
					String path = SelfReferenceUtil.get().getPath();
					Log.info("Attempting to attatch to '{}' with agent '{}'", getPid(), path);
					// Because agent loading will incur a hang,
					// we make a new thread for the success operation.
					if (onSuccess != null) {
						DelayableAction t = new DelayableAction(1000, () -> {
							if (isSuccess.get())
								onSuccess.run();
						});
						t.resetDelay();
						t.start();
					}
					// Attempt to load
					machine.loadAgent(path);
				} catch(URISyntaxException ex) {
					Log.error(ex, "Recaf failed to resolve itself as an agent");
					thrown = ex;
				}catch(IOException ex) {
					Log.error(ex, "Recaf failed to connect to target machine '{}'", getPid());
					thrown = ex;
				} catch(AgentInitializationException ex) {
					Log.error(ex, "Recaf agent failed to initialize in the target machine '{}'", getPid());
					thrown = ex;
				} catch(AgentLoadException ex) {
					Log.error(ex, "Recaf agent crashed in the target machine '{}'", getPid());
					thrown = ex;
				}
				// Handle errors
				if (thrown != null) {
					isSuccess.set(false);
					if(onError != null)
						onError.accept(thrown);
				}
			}).start();
		}

		/**
		 * Detach from the target VM.
		 *
		 * @throws IOException
		 * 		When an error occurred while detaching,
		 * 		likely due to the target VM being dead, or already detached.
		 */
		public void detach() throws IOException {
			machine.detach();
		}
	}
}
