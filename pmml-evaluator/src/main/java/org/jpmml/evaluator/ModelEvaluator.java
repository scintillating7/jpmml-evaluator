/*
 * Copyright (c) 2013 KNIME.com AG, Zurich, Switzerland
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.jpmml.evaluator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.collect.Table;
import org.dmg.pmml.DataDictionary;
import org.dmg.pmml.DataField;
import org.dmg.pmml.DataType;
import org.dmg.pmml.DefineFunction;
import org.dmg.pmml.DerivedField;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.InlineTable;
import org.dmg.pmml.LocalTransformations;
import org.dmg.pmml.MiningField;
import org.dmg.pmml.MiningFunction;
import org.dmg.pmml.MiningSchema;
import org.dmg.pmml.Model;
import org.dmg.pmml.ModelVerification;
import org.dmg.pmml.OpType;
import org.dmg.pmml.Output;
import org.dmg.pmml.OutputField;
import org.dmg.pmml.PMML;
import org.dmg.pmml.Target;
import org.dmg.pmml.Targets;
import org.dmg.pmml.TransformationDictionary;
import org.dmg.pmml.TypeDefinitionField;
import org.dmg.pmml.VerificationField;
import org.dmg.pmml.VerificationFields;

abstract
public class ModelEvaluator<M extends Model> extends ModelManager<M> implements Evaluator {

	private Map<FieldName, DataField> dataFields = Collections.emptyMap();

	private Map<FieldName, DerivedField> derivedFields = Collections.emptyMap();

	private Map<String, DefineFunction> defineFunctions = Collections.emptyMap();

	transient
	private List<FieldName> targetFieldNames = null;

	private Map<FieldName, MiningField> miningFields = Collections.emptyMap();

	private ListMultimap<EnumSet<MiningField.FieldUsage>, FieldName> miningFieldNames = ImmutableListMultimap.of();

	private Map<FieldName, DerivedField> localDerivedFields = Collections.emptyMap();

	private Map<FieldName, Target> targets = Collections.emptyMap();

	transient
	private List<FieldName> outputFieldNames = null;

	private Map<FieldName, OutputField> outputFields = Collections.emptyMap();


	public ModelEvaluator(PMML pmml, Class<? extends M> clazz){
		this(pmml, selectModel(pmml, clazz));
	}

	public ModelEvaluator(PMML pmml, M model){
		super(pmml, model);

		DataDictionary dataDictionary = pmml.getDataDictionary();
		if(dataDictionary.hasDataFields()){
			this.dataFields = CacheUtil.getValue(dataDictionary, ModelEvaluator.dataFieldCache);
		}

		TransformationDictionary transformationDictionary = pmml.getTransformationDictionary();
		if(transformationDictionary != null && transformationDictionary.hasDerivedFields()){
			this.derivedFields = CacheUtil.getValue(transformationDictionary, ModelEvaluator.derivedFieldCache);
		} // End if

		if(transformationDictionary != null && transformationDictionary.hasDefineFunctions()){
			this.defineFunctions = CacheUtil.getValue(transformationDictionary, ModelEvaluator.defineFunctionCache);
		}

		MiningSchema miningSchema = model.getMiningSchema();
		if(miningSchema.hasMiningFields()){
			this.miningFields = CacheUtil.getValue(miningSchema, ModelEvaluator.miningFieldCache);

			this.miningFieldNames = CacheUtil.getValue(miningSchema, ModelEvaluator.miningFieldNameCache);
		}

		LocalTransformations localTransformations = model.getLocalTransformations();
		if(localTransformations != null && localTransformations.hasDerivedFields()){
			this.localDerivedFields = CacheUtil.getValue(localTransformations, ModelEvaluator.localDerivedFieldCache);
		}

		Targets targets = model.getTargets();
		if(targets != null && targets.hasTargets()){
			this.targets = CacheUtil.getValue(targets, ModelEvaluator.targetCache);
		}

		Output output = model.getOutput();
		if(output != null && output.hasOutputFields()){
			this.outputFields = CacheUtil.getValue(output, ModelEvaluator.outputFieldCache);
		}
	}

	abstract
	public Map<FieldName, ?> evaluate(ModelEvaluationContext context);

	@Override
	public DataField getDataField(FieldName name){

		if(Objects.equals(Evaluator.DEFAULT_TARGET, name)){
			return getDataField();
		}

		return this.dataFields.get(name);
	}

	/**
	 * @return A synthetic {@link DataField} describing the default target field.
	 */
	protected DataField getDataField(){
		Model model = getModel();

		MiningFunction miningFunction = model.getMiningFunction();
		switch(miningFunction){
			case REGRESSION:
				return ModelEvaluator.DEFAULT_REGRESSION_TARGET;
			case CLASSIFICATION:
				return ModelEvaluator.DEFAULT_CLASSIFICATION_TARGET;
			case CLUSTERING:
				return ModelEvaluator.DEFAULT_CLUSTERING_TARGET;
			default:
				break;
		}

		return null;
	}

	@Override
	public DerivedField getDerivedField(FieldName name){
		return this.derivedFields.get(name);
	}

	@Override
	public DefineFunction getDefineFunction(String name){
		return this.defineFunctions.get(name);
	}

	@Override
	public List<FieldName> getTargetFields(){

		if(this.targetFieldNames == null){
			this.targetFieldNames = super.getTargetFields();
		}

		return this.targetFieldNames;
	}

	@Override
	public FieldName getTargetField(){
		M model = getModel();

		List<FieldName> targetFields = getTargetFields();

		// "The definition of target fields in the MiningSchema is not required"
		if(targetFields.size() < 1){
			return Evaluator.DEFAULT_TARGET;
		} else

		if(targetFields.size() > 1){
			MiningSchema miningSchema = model.getMiningSchema();

			throw new InvalidFeatureException("Too many target fields", miningSchema);
		}

		return targetFields.get(0);
	}

	@Override
	public MiningField getMiningField(FieldName name){

		if(name == null){
			return null;
		}

		return this.miningFields.get(name);
	}

	@Override
	protected List<FieldName> getMiningFields(EnumSet<MiningField.FieldUsage> types){
		List<FieldName> result = this.miningFieldNames.get(types);

		if(result != null){
			return result;
		}

		return super.getMiningFields(types);
	}

	@Override
	public DerivedField getLocalDerivedField(FieldName name){
		return this.localDerivedFields.get(name);
	}

	@Override
	public Target getTarget(FieldName name){

		if(name == null){
			return null;
		}

		return this.targets.get(name);
	}

	@Override
	public List<FieldName> getOutputFields(){

		if(this.outputFieldNames == null){
			this.outputFieldNames = super.getOutputFields();
		}

		return this.outputFieldNames;
	}

	@Override
	public OutputField getOutputField(FieldName name){
		return this.outputFields.get(name);
	}

	@Override
	public FieldValue prepare(FieldName name, Object value){
		M model = getModel();

		DataField dataField = getDataField(name);
		if(dataField == null){
			throw new MissingFieldException(name, model);
		}

		MiningField miningField = getMiningField(name);
		if(miningField == null){
			throw new EvaluationException();
		}

		MiningField.FieldUsage fieldUsage = miningField.getFieldUsage();
		switch(fieldUsage){
			case ACTIVE:
			case ORDER:
			case GROUP:
				{
					return FieldValueUtil.prepareInputValue(dataField, miningField, value);
				}
			case PREDICTED:
			case TARGET:
				{
					Target target = getTarget(name);

					return FieldValueUtil.prepareTargetValue(dataField, miningField, target, value);
				}
			default:
				throw new UnsupportedFeatureException(miningField, fieldUsage);
		}
	}

	@Override
	public void verify(){
		M model = getModel();

		ModelVerification modelVerification = model.getModelVerification();
		if(modelVerification == null){
			return;
		}

		VerificationBatch batch = CacheUtil.getValue(modelVerification, ModelEvaluator.batchCache);

		List<Map<FieldName, Object>> records = batch.getRecords();

		List<FieldName> activeFields = getActiveFields();
		List<FieldName> groupFields = getGroupFields();

		if(groupFields.size() == 1){
			FieldName groupField = groupFields.get(0);

			records = EvaluatorUtil.groupRows(groupField, records);
		} else

		if(groupFields.size() > 1){
			throw new EvaluationException();
		}

		List<FieldName> targetFields = getTargetFields();
		List<FieldName> outputFields = getOutputFields();

		SetView<FieldName> intersection = Sets.intersection(batch.keySet(), ImmutableSet.copyOf(outputFields));

		for(Map<FieldName, Object> record : records){
			Map<FieldName, Object> arguments = new HashMap<>();

			for(FieldName activeField : activeFields){
				arguments.put(activeField, EvaluatorUtil.prepare(this, activeField, record.get(activeField)));
			}

			Map<FieldName, ?> result = evaluate(arguments);

			// "If there exist VerificationField elements that refer to OutputField elements,
			// then any VerificationField element that refers to a MiningField element whose "usageType=target" should be ignored,
			// because they are considered to represent a dependent variable from the training data set, not an expected output"
			if(intersection.size() > 0){

				for(FieldName outputField : outputFields){
					VerificationField verificationField = batch.get(outputField);

					if(verificationField == null){
						continue;
					}

					verify(record.get(outputField), result.get(outputField), verificationField.getPrecision(), verificationField.getZeroThreshold());
				}
			} else

			// "If there are no such VerificationField elements,
			// then any VerificationField element that refers to a MiningField element whose "usageType=target" should be considered to represent an expected output"
			{
				for(FieldName targetField : targetFields){
					VerificationField verificationField = batch.get(targetField);

					if(verificationField == null){
						continue;
					}

					verify(record.get(targetField), EvaluatorUtil.decode(result.get(targetField)), verificationField.getPrecision(), verificationField.getZeroThreshold());
				}
			}
		}
	}

	/**
	 * @param expected A string or a collection of strings representing the expected value
	 * @param actual The actual value
	 */
	private void verify(Object expected, Object actual, double precision, double zeroThreshold){

		if(expected == null){
			return;
		} // End if

		if(!(actual instanceof Collection)){
			DataType dataType = TypeUtil.getDataType(actual);

			expected = TypeUtil.parseOrCast(dataType, expected);
		}

		boolean acceptable = VerificationUtil.acceptable(expected, actual, precision, zeroThreshold);
		if(!acceptable){
			throw new EvaluationException();
		}
	}

	@Override
	public Map<FieldName, ?> evaluate(Map<FieldName, ?> arguments){
		ModelEvaluationContext context = new ModelEvaluationContext(this);
		context.setArguments(arguments);

		return evaluate(context);
	}

	protected TypeDefinitionField resolveField(FieldName name){
		TypeDefinitionField result = getDataField(name);

		if(result == null){
			result = resolveDerivedField(name);
		}

		return result;
	}

	protected DerivedField resolveDerivedField(FieldName name){
		DerivedField result = getDerivedField(name);

		if(result == null){
			result = getLocalDerivedField(name);
		}

		return result;
	}

	public <V> V getValue(LoadingCache<M, V> cache){
		M model = getModel();

		return CacheUtil.getValue(model, cache);
	}

	public <V> V getValue(Cache<M, V> cache, Callable<? extends V> loader){
		M model = getModel();

		return CacheUtil.getValue(model, cache, loader);
	}

	static
	protected <M extends Model> M selectModel(PMML pmml, Class<? extends M> clazz){
		List<Model> models = pmml.getModels();

		Iterable<? extends M> filteredModels = Iterables.filter(models, clazz);

		M model = Iterables.getFirst(filteredModels, null);
		if(model == null){
			throw new InvalidFeatureException(pmml);
		}

		return model;
	}

	static
	private ListMultimap<EnumSet<MiningField.FieldUsage>, FieldName> parseMiningFieldNames(List<MiningField> miningFields){
		Set<EnumSet<MiningField.FieldUsage>> keys = ImmutableSet.of(ModelManager.ACTIVE_TYPES, ModelManager.GROUP_TYPES, ModelManager.ORDER_TYPES, ModelManager.TARGET_TYPES);

		ListMultimap<EnumSet<MiningField.FieldUsage>, FieldName> result = ArrayListMultimap.create();

		for(MiningField miningField : miningFields){

			for(EnumSet<MiningField.FieldUsage> key : keys){

				if(key.contains(miningField.getFieldUsage())){
					result.put(key, miningField.getName());
				}
			}
		}

		return result;
	}

	static
	private VerificationBatch parseModelVerification(ModelVerification modelVerification){
		VerificationBatch result = new VerificationBatch();

		VerificationFields verificationFields = modelVerification.getVerificationFields();
		if(verificationFields == null){
			throw new InvalidFeatureException(modelVerification);
		}

		for(VerificationField verificationField : verificationFields){
			result.put(verificationField.getField(), verificationField);
		}

		InlineTable inlineTable = modelVerification.getInlineTable();
		if(inlineTable == null){
			throw new InvalidFeatureException(modelVerification);
		}

		Table<Integer, String, String> table = InlineTableUtil.getContent(inlineTable);

		List<Map<FieldName, Object>> records = new ArrayList<>();

		Set<Integer> rowKeys = table.rowKeySet();
		for(Integer rowKey : rowKeys){
			Map<String, String> row = table.row(rowKey);

			Map<FieldName, Object> record = new LinkedHashMap<>();

			for(VerificationField verificationField : verificationFields){
				FieldName name = verificationField.getField();
				String column = verificationField.getColumn();

				if(column == null){
					column = name.getValue();
				} // End if

				if(!row.containsKey(column)){
					continue;
				}

				record.put(name, row.get(column));
			}

			records.add(record);
		}

		Integer recordCount = modelVerification.getRecordCount();
		if(recordCount != null && recordCount.intValue() != records.size()){
			throw new InvalidFeatureException(inlineTable);
		}

		result.setRecords(records);

		return result;
	}

	private static final DataField DEFAULT_REGRESSION_TARGET = new DataField(Evaluator.DEFAULT_TARGET, OpType.CONTINUOUS, DataType.DOUBLE);
	private static final DataField DEFAULT_CLASSIFICATION_TARGET = new DataField(Evaluator.DEFAULT_TARGET, OpType.CATEGORICAL, DataType.STRING);
	private static final DataField DEFAULT_CLUSTERING_TARGET = new DataField(Evaluator.DEFAULT_TARGET, OpType.CATEGORICAL, DataType.STRING);

	private static final LoadingCache<DataDictionary, Map<FieldName, DataField>> dataFieldCache = CacheUtil.buildLoadingCache(new CacheLoader<DataDictionary, Map<FieldName, DataField>>(){

		@Override
		public Map<FieldName, DataField> load(DataDictionary dataDictionary){
			return IndexableUtil.buildMap(dataDictionary.getDataFields());
		}
	});

	private static final LoadingCache<TransformationDictionary, Map<FieldName, DerivedField>> derivedFieldCache = CacheUtil.buildLoadingCache(new CacheLoader<TransformationDictionary, Map<FieldName, DerivedField>>(){

		@Override
		public Map<FieldName, DerivedField> load(TransformationDictionary transformationDictionary){
			return IndexableUtil.buildMap(transformationDictionary.getDerivedFields());
		}
	});

	private static final LoadingCache<TransformationDictionary, Map<String, DefineFunction>> defineFunctionCache = CacheUtil.buildLoadingCache(new CacheLoader<TransformationDictionary, Map<String, DefineFunction>>(){

		@Override
		public Map<String, DefineFunction> load(TransformationDictionary transformationDictionary){
			return IndexableUtil.buildMap(transformationDictionary.getDefineFunctions());
		}
	});

	private static final LoadingCache<MiningSchema, Map<FieldName, MiningField>> miningFieldCache = CacheUtil.buildLoadingCache(new CacheLoader<MiningSchema, Map<FieldName, MiningField>>(){

		@Override
		public Map<FieldName, MiningField> load(MiningSchema miningSchema){
			return IndexableUtil.buildMap(miningSchema.getMiningFields());
		}
	});

	private static final LoadingCache<MiningSchema, ListMultimap<EnumSet<MiningField.FieldUsage>, FieldName>> miningFieldNameCache = CacheUtil.buildLoadingCache(new CacheLoader<MiningSchema, ListMultimap<EnumSet<MiningField.FieldUsage>, FieldName>>(){

		@Override
		public ListMultimap<EnumSet<MiningField.FieldUsage>, FieldName> load(MiningSchema miningSchema){
			return ImmutableListMultimap.copyOf(parseMiningFieldNames(miningSchema.getMiningFields()));
		}
	});

	private static final LoadingCache<LocalTransformations, Map<FieldName, DerivedField>> localDerivedFieldCache = CacheUtil.buildLoadingCache(new CacheLoader<LocalTransformations, Map<FieldName, DerivedField>>(){

		@Override
		public Map<FieldName, DerivedField> load(LocalTransformations localTransformations){
			return IndexableUtil.buildMap(localTransformations.getDerivedFields());
		}
	});

	private static final LoadingCache<Targets, Map<FieldName, Target>> targetCache = CacheUtil.buildLoadingCache(new CacheLoader<Targets, Map<FieldName, Target>>(){

		@Override
		public Map<FieldName, Target> load(Targets targets){
			return IndexableUtil.buildMap(targets.getTargets());
		}
	});

	private static final LoadingCache<Output, Map<FieldName, OutputField>> outputFieldCache = CacheUtil.buildLoadingCache(new CacheLoader<Output, Map<FieldName, OutputField>>(){

		@Override
		public Map<FieldName, OutputField> load(Output output){
			return IndexableUtil.buildMap(output.getOutputFields());
		}
	});

	static
	private class VerificationBatch extends LinkedHashMap<FieldName, VerificationField> {

		private List<Map<FieldName, Object>> records = null;


		public List<Map<FieldName, Object>> getRecords(){
			return this.records;
		}

		private void setRecords(List<Map<FieldName, Object>> records){
			this.records = records;
		}
	}

	private static final LoadingCache<ModelVerification, VerificationBatch> batchCache = CacheUtil.buildLoadingCache(new CacheLoader<ModelVerification, VerificationBatch>(){

		@Override
		public VerificationBatch load(ModelVerification modelVerification){
			return parseModelVerification(modelVerification);
		}
	});
}