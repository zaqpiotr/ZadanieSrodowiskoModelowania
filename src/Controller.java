import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import models.Bind;

public class Controller {

	private static final String NEW_LINE = "\n";
	private static final String TAB = "\t";
	private static final int FIELD_NAME_INDEX = 0;
	private static final int VALUES_PART_INDEX = 1;
	private static final String ONE_OR_MORE_WHITESCE_CHARACTERS = "\\s+";
	private static final String LATA = "LATA";

	private Class<?> modelClass;
	private Map<String, List<Double>> dataSource;
	private Object modelObject;
	private Bindings bindingsFieldsFromScript;

	public Controller(String modelName) {
		try {
			this.modelClass = Class.forName("models." + modelName);
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
		this.dataSource = new LinkedHashMap<>();
	}

	public Controller readDataFrom(String fname) {
		try {
			Path path = Paths.get(fname);
			Files.lines(path).forEach(line -> writeToDataSource(line));
		} catch (IOException e) {
			e.printStackTrace();
		}
		return this;
	}

	private void writeToDataSource(String line) {
		String[] tabSplittedLine = line.split(TAB);
		String fieldName = tabSplittedLine[FIELD_NAME_INDEX]; // lewa strona od tab
		String fieldValuesLine = tabSplittedLine[VALUES_PART_INDEX]; // po prawo od tab
		List<Double> values = spaceSeparatedStringValuesToDoubleList(fieldValuesLine);
		if (fieldName.equals(LATA)) {
			this.dataSource.put(fieldName, values);
		} else {
			this.dataSource.put(fieldName, prepareValuesList(values));
		}
	}

	private List<Double> spaceSeparatedStringValuesToDoubleList(String line) {
		String[] lineValues = line.split(ONE_OR_MORE_WHITESCE_CHARACTERS);
		return Arrays.stream(lineValues)
				.map(value -> Double.parseDouble(value))
				.collect(Collectors.toList());
	}

	private List<Double> prepareValuesList(List<Double> fieldValues) {
		if(fieldValues.size() == getYearsNumberCount()) {
			return fieldValues;
		}
		int indexFieldToRepeat = fieldValues.size();
		for (int i = indexFieldToRepeat; i < getYearsNumberCount(); i++) {
			fieldValues.add(fieldValues.get(indexFieldToRepeat-1));
		}
		return fieldValues;
	}

	public Controller runModel() {
		try {
			this.modelObject = modelClass.newInstance();
			List<Field> modelFieldList = getFilteredFieldsFromModel(this.modelClass, field -> hasAnnotationBindAndIsArrayOfDouble(field));

			modelFieldList.forEach(field -> setValueTo(this.modelObject, field, this.dataSource));

			Field fieldLL = this.modelClass.getDeclaredField("LL");
			fieldLL.setAccessible(true);
			fieldLL.set(this.modelObject, getYearsNumberCount());

			this.modelClass.getDeclaredMethod("run").invoke(this.modelObject);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return this;
	}

	private void setValueTo(Object modelObject, Field field, Map<String, List<Double>> dataSource) {
			List<Double> fieldValue = getFieldValueByName(field, dataSource);
			if (fieldValue == null) {
				return;
			}

			try {
				field.set(modelObject, toPrimitive(fieldValue));
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			}
	}

	private List<Double> getFieldValueByName(Field field, Map<String, List<Double>> dataSource) {
		return dataSource.get(field.getName());
	}

	private double[] toPrimitive(List<Double> fieldValues) {
		return fieldValues.stream()
				.mapToDouble(doubleValue -> doubleValue)
				.toArray();
	}

	public Controller runScriptFromFile(String scriptLocation) {
		List<Field> fieldsWithBindAnnotation = getFilteredFieldsFromModel(this.modelClass, filteringPredicate -> filteringPredicate.isAnnotationPresent(Bind.class));
		ScriptEngineManager manager = new ScriptEngineManager();
		ScriptEngine engine = manager.getEngineByName("groovy");
		this.bindingsFieldsFromScript = engine.createBindings();

		try {
			for (Field field : fieldsWithBindAnnotation) {
				field.setAccessible(true);
				this.bindingsFieldsFromScript.put(field.getName(), field.get(this.modelObject));
			}
			engine.eval(new FileReader(scriptLocation), this.bindingsFieldsFromScript);
			getScriptFields(fieldsWithBindAnnotation, this.bindingsFieldsFromScript, this.modelObject);
		} catch (ScriptException | IllegalAccessException | FileNotFoundException e) {
			e.printStackTrace();
		}

		return this;
	}

	public String getResultsAsTsv() {
		StringBuilder resultStringBuilder = new StringBuilder();
		resultStringBuilder.append("LATA\t");
		List<Double> yearsValues = dataSource.get(LATA);

		for (int i = 0; i < yearsValues.size(); i++) {
			resultStringBuilder.append((int) yearsValues.get(i).doubleValue())
					.append(TAB);
		}
		resultStringBuilder.append(NEW_LINE);

		List<Field> modelFieldsWithBindAnnotation = getFilteredFieldsFromModel(this.modelClass, field -> hasAnnotationBindAndIsArrayOfDouble(field));

		resultStringBuilder.append(fieldsFromModel(modelFieldsWithBindAnnotation, this.modelObject));
		resultStringBuilder.append(fieldsFromScript(modelFieldsWithBindAnnotation, this.bindingsFieldsFromScript));
		return resultStringBuilder.toString();
	}

	private void getScriptFields(List<Field> bindedFields, Bindings bindings, Object modelObject) throws IllegalAccessException {
		for (Field field : bindedFields) {
			String name = field.getName();
			Object value = bindings.get(name);
			field.set(modelObject, value);
		}
	}

	private String fieldsFromScript(List<Field> modelFieldsWithBindAnnotation, Bindings bindings) {
		StringBuilder stringBuilder = new StringBuilder();
		for (String fieldName : bindings.keySet()) {
			if (fieldNotExistsInModel(modelFieldsWithBindAnnotation, fieldName)) {
				try {
					Object field = bindings.get(fieldName);
					if(isArrayOfDouble(field)) {
						stringBuilder.append(appendFieldNameAndValues(fieldName, (double[]) field));
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		return stringBuilder.toString();
	}

	private String fieldsFromModel(List<Field> modelFieldsWithBindAnnotation, Object modelObject) {
		StringBuilder stringBuilder = new StringBuilder();
		for (Field field : modelFieldsWithBindAnnotation) {
				try {
					stringBuilder.append(appendFieldNameAndValues(field.getName(), (double[]) field.get(modelObject)));
				} catch (IllegalAccessException e) {
					e.printStackTrace();
				}
		}
		return stringBuilder.toString();
	}

	private String appendFieldNameAndValues(String fieldName, double[] field) {
		StringBuilder stringBuilder = new StringBuilder();
		double[] values = field;
		stringBuilder
				.append(fieldName)
				.append(TAB);
		for (int i = 0; i < values.length; i++) {
			stringBuilder
					.append(values[i])
					.append(TAB);
		}
		stringBuilder.append(NEW_LINE);
		return stringBuilder.toString();
	}

	private boolean isArrayOfDouble(Object obj) {
		if (obj instanceof double[]) {
			return true;
		}
		return false;
	}

	private boolean fieldNotExistsInModel(List<Field> bindedFields, String fieldName) {
		return ! bindedFields.stream().anyMatch(field -> field.getName().equals(fieldName));
	}

	private int getYearsNumberCount() {
		return dataSource.get(LATA).size();
	}

	private List<Field> getFilteredFieldsFromModel(Class<?> modelClass, Predicate<Field> filteringMethod) {
		List<Field> list = new ArrayList<>();
		for (Field field : modelClass.getDeclaredFields()) {
			if (filteringMethod.test(field)) {
				field.setAccessible(true);
				list.add(field);
			}
		}
		return list;
	}

	private boolean hasAnnotationBindAndIsArrayOfDouble(Field field) {
		return field.isAnnotationPresent(Bind.class) && field.getType().equals(double[].class);
	}

}
